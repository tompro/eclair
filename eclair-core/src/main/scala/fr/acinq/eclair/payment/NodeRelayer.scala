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

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC, Upstream}
import fr.acinq.eclair.payment.MultiPartPaymentLifecycle.SendMultiPartPayment
import fr.acinq.eclair.payment.PaymentInitiator.SendPaymentConfig
import fr.acinq.eclair.payment.PaymentLifecycle.SendPayment
import fr.acinq.eclair.router.{RouteParams, Router}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, Features, MilliSatoshi, NodeParams, nodeFee}

/**
 * Created by t-bast on 10/10/2019.
 */

/**
 * The Node Relayer is used to relay an upstream payment to a downstream node.
 * It aggregates incoming HTLCs (in case multi-part was used upstream) and then forwards the requested amount.
 */
class NodeRelayer(nodeParams: NodeParams, relayer: ActorRef, router: ActorRef, commandBuffer: ActorRef, register: ActorRef) extends Actor with ActorLogging {

  // TODO: @t-bast: if fees/cltv insufficient (could not find route) send special error (sender can retry with higher fees/cltv)?
  // TODO: @t-bast: handle HTLC errors (needs new Origin, its codec, pending relay DB, convert onion errors, etc?)
  // TODO: @t-bast: add Kamon counters to monitor the size of pendingIncoming/Outgoing (do the same for multi-part?)

  import NodeRelayer._

  override def receive: Receive = main(Map.empty, Map.empty)

  def main(pendingIncoming: Map[ByteVector32, PendingRelay], pendingOutgoing: Map[UUID, List[MultiPartPaymentHandler.PendingPayment]]): Receive = {
    // We make sure we receive all payment parts before forwarding to the next trampoline node.
    case IncomingPacket.NodeRelayPacket(add, outer, inner, next) => outer.paymentSecret match {
      case None => rejectHtlc(add)
      case Some(secret) => pendingIncoming.get(add.paymentHash) match {
        case Some(PendingRelay(_, secret2, _, _, _, _)) if secret != secret2 => rejectHtlc(add)
        case Some(relay) =>
          relay.handler forward MultiPartPaymentHandler.MultiPartHtlc(outer.totalAmount, add)
          // We use the lowest cltv expiry of the incoming htlc set.
          val expiry = if (outer.expiry < relay.expiry) outer.expiry else relay.expiry
          context become main(pendingIncoming + (add.paymentHash -> relay.copy(expiry = expiry, htlcs = add +: relay.htlcs)), pendingOutgoing)
        case None =>
          val handler = context.actorOf(MultiPartPaymentHandler.props(nodeParams, add.paymentHash, outer.totalAmount, self))
          handler forward MultiPartPaymentHandler.MultiPartHtlc(outer.totalAmount, add)
          context become main(pendingIncoming + (add.paymentHash -> PendingRelay(Seq(add), secret, outer.expiry, inner, next, handler)), pendingOutgoing)
      }
    }

    // We always fail extraneous HTLCs. They are a spec violation from the sender, but harmless in the relay case.
    // By failing them fast (before the payment has reached the final recipient) there's a good chance the sender
    // won't lose any money.
    case MultiPartPaymentHandler.ExtraHtlcReceived(_, p, failure) => rejectPayment(p, failure)

    case MultiPartPaymentHandler.MultiPartHtlcFailed(paymentHash, failure, parts) =>
      log.warning(s"could not relay payment with paymentHash=$paymentHash (paidAmount=${parts.map(_.payment.amount).sum} failure=$failure)")
      pendingIncoming.get(paymentHash).foreach(_.handler ! PoisonPill)
      parts.foreach(p => rejectPayment(p, Some(failure)))
      context become main(pendingIncoming - paymentHash, pendingOutgoing)

    case MultiPartPaymentHandler.MultiPartHtlcSucceeded(paymentHash, parts) => pendingIncoming.get(paymentHash) match {
      case Some(PendingRelay(htlcs, _, expiry, nextPayload, _, handler)) =>
        log.info(s"relaying trampoline payment with paymentHash=$paymentHash")
        handler ! PoisonPill
        val paymentId = relay(paymentHash, Upstream.NodeRelayed(htlcs), nextPayload)
        // TODO: @t-bast: maybe not exactly parts that we want to store but rather htlcs?
        // Maybe MultiPartPaymentHandler's PendingPayment should contain the whole UpdateAddHtlc, simpler that way.
        context become main(pendingIncoming - paymentHash, pendingOutgoing + (paymentId -> parts))
      case None => throw new RuntimeException(s"could not find pending incoming payment (paymentHash=$paymentHash)")
    }

    case PaymentSent(id, _, paymentPreimage, _) =>
      // TODO: @t-bast: emit PaymentRelayed event
      pendingOutgoing.get(id).foreach(_.foreach(p => fulfillPayment(p, paymentPreimage)))
      context become main(pendingIncoming, pendingOutgoing - id)

    case PaymentFailed(id, _, _, _) =>
      // TODO: @t-bast: try to extract the most meaningful error to return upstream (from the downstream failures)
      pendingOutgoing.get(id).foreach(_.foreach(p => rejectPayment(p)))
      context become main(pendingIncoming, pendingOutgoing - id)

    // TODO: @t-bast: do I need to handle some Status.Failure() events? Probably not, unless Relayer forwards them to me
    // Check logs during E2E tests to verify

  }

  private def relay(paymentHash: ByteVector32, upstream: Upstream.NodeRelayed, payloadOut: Onion.NodeRelayPayload): UUID = {
    import fr.acinq.eclair.payment.PaymentRequest.Features._
    val paymentId = UUID.randomUUID()
    val paymentCfg = SendPaymentConfig(paymentId, paymentId, None, paymentHash, payloadOut.outgoingNodeId, upstream, None, storeInDb = false, publishEvent = false)
    val routeParams = computeRouteParams(nodeParams, upstream.adds.map(_.amountMsat).sum, upstream.adds.map(_.cltvExpiry).min, payloadOut.amountToForward)
    val routingHints = payloadOut.invoiceHints.map(_.map(_.toSeq).toSeq).getOrElse(Nil)
    val allowMultiPart = payloadOut.invoiceFeatures.exists(f => Features.hasFeature(f, BASIC_MULTI_PART_PAYMENT_OPTIONAL) || Features.hasFeature(f, BASIC_MULTI_PART_PAYMENT_MANDATORY))
    if (allowMultiPart) {
      val payFSM = context.actorOf(MultiPartPaymentLifecycle.props(nodeParams, paymentCfg, relayer, router, register))
      // TODO: @t-bast: should this be a new payment secret?
      val payment = SendMultiPartPayment(paymentHash, payloadOut.paymentSecret.get, payloadOut.outgoingNodeId, payloadOut.amountToForward, payloadOut.outgoingCltv, nodeParams.maxPaymentAttempts, routingHints, Some(routeParams))
      payFSM ! payment
    } else {
      val payFSM = context.actorOf(PaymentLifecycle.props(nodeParams, paymentCfg, router, register))
      // TODO: @t-bast: figure out what payment secret we should send here.
      val finalPayload = Onion.createSinglePartPayload(payloadOut.amountToForward, payloadOut.outgoingCltv, payloadOut.paymentSecret)
      val payment = SendPayment(paymentHash, payloadOut.outgoingNodeId, finalPayload, nodeParams.maxPaymentAttempts, routingHints, Some(routeParams))
      payFSM ! payment
    }
    paymentId
  }

  private def rejectHtlc(add: UpdateAddHtlc): Unit = {
    val cmdFail = CMD_FAIL_HTLC(add.id, Right(IncorrectOrUnknownPaymentDetails(add.amountMsat, nodeParams.currentBlockHeight)), commit = true)
    commandBuffer ! CommandBuffer.CommandSend(add.channelId, add.id, cmdFail)
  }

  private def rejectPayment(p: MultiPartPaymentHandler.PendingPayment, failure: Option[FailureMessage] = None): Unit = {
    val failureMessage = failure.getOrElse(IncorrectOrUnknownPaymentDetails(p.payment.amount, nodeParams.currentBlockHeight))
    commandBuffer ! CommandBuffer.CommandSend(p.payment.fromChannelId, p.htlcId, CMD_FAIL_HTLC(p.htlcId, Right(failureMessage), commit = true))
  }

  private def fulfillPayment(p: MultiPartPaymentHandler.PendingPayment, paymentPreimage: ByteVector32): Unit = {
    val cmdFulfill = CMD_FULFILL_HTLC(p.htlcId, paymentPreimage, commit = true)
    commandBuffer ! CommandBuffer.CommandSend(p.payment.fromChannelId, p.htlcId, cmdFulfill)
  }

}

object NodeRelayer {

  def props(nodeParams: NodeParams, relayer: ActorRef, router: ActorRef, commandBuffer: ActorRef, register: ActorRef) = Props(classOf[NodeRelayer], nodeParams, relayer, router, commandBuffer, register)

  /**
   * We start by aggregating an incoming HTLC set. Once we received the whole set, we will compute a route to the next
   * trampoline node and forward the payment.
   *
   * @param htlcs       received incoming HTLCs for this set.
   * @param secret      all incoming HTLCs in this set must have the same secret to protect against probing / fee theft.
   * @param expiry      expiry of the incoming HTLC set. TODO: @t-bast: can be inferred from htlcs, remove
   * @param nextPayload relay instructions (should be identical across HTLCs in this set).
   * @param nextPacket  trampoline onion to relay to the next trampoline node.
   * @param handler     actor handling the aggregation of the incoming HTLC set.
   */
  case class PendingRelay(htlcs: Seq[UpdateAddHtlc], secret: ByteVector32, expiry: CltvExpiry, nextPayload: Onion.NodeRelayPayload, nextPacket: OnionRoutingPacket, handler: ActorRef)

  /** Compute route params that honor our fee and cltv requirements. */
  def computeRouteParams(nodeParams: NodeParams, amountIn: MilliSatoshi, expiryIn: CltvExpiry, amountOut: MilliSatoshi): RouteParams = {
    val routeMaxCltv = expiryIn - nodeParams.expiryDeltaBlocks.toCltvExpiry(nodeParams.currentBlockHeight)
    val routeMaxFee = amountIn - amountOut - nodeFee(nodeParams.feeBase, nodeParams.feeProportionalMillionth, amountOut)
    Router.getDefaultRouteParams(nodeParams.routerConf).copy(
      maxFeeBase = routeMaxFee,
      routeMaxCltv = routeMaxCltv,
      maxFeePct = 0 // we disable percent-based max fee calculation, we're only interested in collecting our node fee
    )
  }

}
