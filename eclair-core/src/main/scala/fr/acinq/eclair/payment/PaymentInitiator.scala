/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.payment

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.channel.Channel
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.PaymentLifecycle.{SendPayment, SendPaymentToRoute}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.{RouteParams, TrampolineHop}
import fr.acinq.eclair.wire.Onion.{FinalLegacyPayload, FinalTlvPayload}
import fr.acinq.eclair.wire.OnionTlv._
import fr.acinq.eclair.wire.{Onion, OnionTlv, TlvStream}
import fr.acinq.eclair.{CltvExpiryDelta, LongToBtcAmount, MilliSatoshi, NodeParams}

/**
 * Created by PM on 29/08/2016.
 */
class PaymentInitiator(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef, register: ActorRef) extends Actor with ActorLogging {

  import PaymentInitiator._

  override def receive: Receive = {
    case r: SendPaymentRequest =>
      val paymentId = UUID.randomUUID()
      val finalExpiry = r.finalExpiry(nodeParams.currentBlockHeight)
      r.paymentRequest match {
        case Some(invoice) if invoice.features.allowMultiPart =>
          r.predefinedRoute match {
            case Nil => spawnMultiPartPaymentFsm(paymentId, r) forward r
            case hops => spawnPaymentFsm(paymentId, r) forward SendPaymentToRoute(r.paymentHash, hops, Onion.createMultiPartPayload(r.amount, invoice.amount.getOrElse(r.amount), finalExpiry, invoice.paymentSecret.get))
          }
        case _ =>
          val payFsm = spawnPaymentFsm(paymentId, r)
          r.predefinedRoute match {
            case Nil => r.trampolineId match {
              case Some(trampolineId) =>
                val trampolinePayload = PaymentLifecycle.buildTrampolinePayload(r.paymentHash, r.targetNodeId, trampolineId, r.amount, finalExpiry, r.trampolineFees, r.trampolineDelta)
                payFsm forward SendPayment(r.paymentHash, trampolineId, trampolinePayload, r.maxAttempts, r.assistedRoutes, r.routeParams)
              case None =>
                // NB: we only generate legacy payment onions for now for maximum compatibility.
                payFsm forward SendPayment(r.paymentHash, r.targetNodeId, FinalLegacyPayload(r.amount, finalExpiry), r.maxAttempts, r.assistedRoutes, r.routeParams)
            }
            // TODO: @t-bast: remove this test hook once done
            case b :: d :: Nil =>
              // We use b as trampoline hop to reach d:
              //       .------.
              //      /        \
              // a -> b -> c -> d
              val (amount_b, expiry_b, trampolinePayloads) = PaymentLifecycle.buildPayloads(
                TrampolineHop(b, d, nodeParams.expiryDeltaBlocks + nodeParams.expiryDeltaBlocks, 3000 msat) :: Nil,
                FinalTlvPayload(TlvStream[OnionTlv](AmountToForward(r.amount), OutgoingCltv(finalExpiry))))
              val Sphinx.PacketAndSecrets(trampolineOnion, _) = PaymentLifecycle.buildOnion(Sphinx.TrampolinePacket)(Seq(b, d), trampolinePayloads, r.paymentHash)
              val payload_b = FinalTlvPayload(TlvStream[OnionTlv](AmountToForward(amount_b), OutgoingCltv(expiry_b), TrampolineOnion(trampolineOnion)))
              payFsm forward SendPaymentToRoute(r.paymentHash, nodeParams.nodeId :: b :: Nil, payload_b)
            case hops => payFsm forward SendPaymentToRoute(r.paymentHash, hops, FinalLegacyPayload(r.amount, finalExpiry))
          }
      }
      sender ! paymentId
  }

  def spawnPaymentFsm(paymentId: UUID, r: SendPaymentRequest): ActorRef = {
    val paymentCfg = SendPaymentConfig(paymentId, paymentId, r.externalId, r.paymentHash, r.targetNodeId, r.paymentRequest, storeInDb = true, publishEvent = true)
    context.actorOf(PaymentLifecycle.props(nodeParams, paymentCfg, router, register))
  }

  def spawnMultiPartPaymentFsm(paymentId: UUID, r: SendPaymentRequest): ActorRef = {
    val paymentCfg = SendPaymentConfig(paymentId, paymentId, r.externalId, r.paymentHash, r.targetNodeId, r.paymentRequest, storeInDb = true, publishEvent = true)
    context.actorOf(MultiPartPaymentLifecycle.props(nodeParams, paymentCfg, relayer, router, register))
  }

}

object PaymentInitiator {

  def props(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef, register: ActorRef) = Props(classOf[PaymentInitiator], nodeParams, router, relayer, register)

  case class SendPaymentRequest(amount: MilliSatoshi,
                                paymentHash: ByteVector32,
                                targetNodeId: PublicKey,
                                maxAttempts: Int,
                                finalExpiryDelta: CltvExpiryDelta = Channel.MIN_CLTV_EXPIRY_DELTA,
                                paymentRequest: Option[PaymentRequest] = None,
                                externalId: Option[String] = None,
                                predefinedRoute: Seq[PublicKey] = Nil,
                                assistedRoutes: Seq[Seq[ExtraHop]] = Nil,
                                routeParams: Option[RouteParams] = None,
                                // TODO: the caller should provide these values and increase them before retrying if he receives a payment error
                                // trampolineId: Option[PublicKey] = Some(PublicKey(hex"03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134")), // this is endurance (testnet)
                                trampolineId: Option[PublicKey] = None,
                                trampolineFees: MilliSatoshi = 0 msat,
                                trampolineDelta: CltvExpiryDelta = Channel.MIN_CLTV_EXPIRY_DELTA) {
    // We add one block in order to not have our htlcs fail when a new block has just been found.
    def finalExpiry(currentBlockHeight: Long) = finalExpiryDelta.toCltvExpiry(currentBlockHeight + 1)
  }

  case class SendPaymentConfig(id: UUID,
                               parentId: UUID,
                               externalId: Option[String],
                               paymentHash: ByteVector32,
                               targetNodeId: PublicKey,
                               paymentRequest: Option[PaymentRequest],
                               storeInDb: Boolean,
                               publishEvent: Boolean)

}
