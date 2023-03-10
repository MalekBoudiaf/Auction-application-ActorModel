package solutions

import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}

import scala.concurrent.duration.*
import scala.concurrent.duration.FiniteDuration
import scala.util.Random

case class Bidder(username: String, bankAccount: Int)
case class Bid(bidder: Bidder, biddingPrice: Int, auction: Auction)
case class Auction(item:String, price: Int, bids: List[Bid], availabilityTime: Int, paymentDue: Int)
case class Seller(name: String, address: String)

trait BidSys
// seller messages... could have been moved inside the seller actor definition like all other actors
trait SellerMessages extends BidSys
case class PublishAuction(auction: Auction) extends SellerMessages
case class AuctionPublished(auction: Auction) extends SellerMessages
case class RemoveAuction(auction: Auction) extends SellerMessages


object SellerActor {

  def apply(sellerName: String ,auctionFactory: ActorRef[BidSys]): Behavior[BidSys] = {
    sellerActor(sellerName, auctionFactory, List[Auction]())
  }

  private def sellerActor(sellerName: String, auctionFactory: ActorRef[BidSys], publishedAuctions: List[Auction]): Behavior[BidSys] =
    Behaviors.receive { (context, message) =>
      message match {
        case PublishAuction(auction: Auction) =>
          context.log.info(s"seller ${sellerName} requesting auction factory to create auction ${auction.item} - inside seller actor")
          // send a request to auctionFactory actor to create a new auction
          auctionFactory ! AuctionFactoryActor.AddAuction(auction, context.self)
          Behaviors.same
        case AuctionPublished(createdAuction: Auction) =>
          context.log.info(s"auction ${createdAuction.item} created for seller ${sellerName} - inside seller actor")
          sellerActor(sellerName, auctionFactory, createdAuction :: publishedAuctions)
        case RemoveAuction(auctionToRemove: Auction) =>
          context.log.info(s"auction ${auctionToRemove.item} Removed for ${sellerName} - inside seller actor")
          sellerActor(sellerName, auctionFactory, publishedAuctions.filterNot(_.item.equals(auctionToRemove.item)))
          // TODO : should remove auction from Ebay actor too!!
          // TODO : should also terminate its actorRef
      }
    }}

object BidderActor:

  case class MakeBid() extends BidSys
  case class ProvideAuctions(availableAuctions: List[(Auction, Int)]) extends BidSys
  case class NotifyNewTopBid(newTopBid: Int, auction: Auction) extends BidSys
  case class RequestToPay(forAuction: Auction, replyTo: ActorRef[BidSys]) extends BidSys
  case class ReBidRequest(auction: Auction, highestBid: Int, replyTo: ActorRef[BidSys]) extends BidSys

  def apply(auctionFactory: ActorRef[BidSys], ebay: ActorRef[BidSys], bidder: Bidder): Behavior[BidSys] ={
    bidderBehaviour(auctionFactory, ebay, bidder)
  }

  private def bidderBehaviour(auctionFactory: ActorRef[BidSys], ebay: ActorRef[BidSys], bidder: Bidder): Behavior[BidSys] =
      Behaviors.receive { (context, message) =>
        context.log.info("Bidder recived message!")
        message match {
          case MakeBid()=>
            context.log.info(s"bid request received from bidder ${bidder.username} - inside bidder actor ${bidder.username}")
            // requesting the updated list of available auctions from ebay
            ebay ! EbayActor.RequestAuctionList(context.self)
            Behaviors.same
          case ProvideAuctions(availableAuctions: List[(Auction,Int)])=>
            context.log.info(s"available auctions received from Ebay - inside bidder actor ${bidder.username}")
            // getting back the available auctions and their highest bid from ebay
            if(availableAuctions.isEmpty){
              context.log.info(s"can't bid, No auctions available yet - inside bidder actor ${bidder.username}")
            }else {
              // choosing a random auction to bid for
              val randomizer = new Random()
              val (randomAuction, highestBid) = availableAuctions(randomizer.nextInt(availableAuctions.length))
              context.log.info(s"a bid from bidder ${bidder.username} is added for the random auction ${randomAuction.item}- inside bidder actor ${bidder.username}")
              // bidding on the chosen auction
              // create a Bid object from the bidder and the auction Bid(bidder, biddingPrice, auction)
              // bidding price is highest bid on that auction + 50
              val bid: Bid = Bid(bidder, highestBid+50 , randomAuction)
              // request auctionFactory to add this to the random auction
              auctionFactory ! AuctionFactoryActor.AddBid(bid, context.self)
            }
            Behaviors.same
          case NotifyNewTopBid(newTopBid: Int, auction: Auction) =>
            context.log.info(s"New Top Bid Notification received. New top bid of ${newTopBid} for auction ${auction.item}- inside bidder actor ${bidder.username}")
            Behaviors.same
          case RequestToPay(forAuction: Auction, replyTo: ActorRef[BidSys]) =>
            // this bidder is the winner for an auction so it replies to the auction with whether it wants to pay or not
            context.log.info(s"bidder: ${bidder.username} won the auction: ${forAuction.item} and is requested to pay")
            // To simulate whether a winner pays or not we randomly choose each time
            val decision = List(true, false)
            replyTo ! AuctionActor.WinnerResponse(false, forAuction)
            Behaviors.same
          case ReBidRequest(auction: Auction, highestBid: Int, replyTo: ActorRef[BidSys]) =>
            val decision = List(true, false)
            if(decision(Random.nextInt(2))){
              // accept to rebid and send new bidding price
              replyTo ! AuctionActor.ReBidResponse(true, highestBid + 50, bidder)
            }else{
              // refuse to rebid
              replyTo ! AuctionActor.ReBidResponse(false, highestBid , bidder)
            }
            Behaviors.same
        }
      }

object EbayActor :

  case class RequestAuctionList(replyTo: ActorRef[BidSys]) extends BidSys
  case class NewAuctionAdded(newAuction: Auction) extends BidSys
  case class HighestBidChanged(auction:Auction, highestBid: Int) extends BidSys

  var auctionList: List[(Auction,Int)]= List[(Auction,Int)]()

  def apply(): Behavior[BidSys] = Behaviors.receive { (context, message) =>
    context.log.info("Ebay actor recived message!")
    message match {
      case RequestAuctionList(replyTo: ActorRef[BidSys])=>
        context.log.info("sending back the available auctions to a bidder - inside the EbayActor")
        // bidder requesting the list of available auctions so send the list back
        replyTo ! BidderActor.ProvideAuctions(auctionList)
      case NewAuctionAdded(newAuction: Auction)=>
        context.log.info(s"Ebay received notification for a new added auction: ${newAuction.item}")
        // update the List of available auctions with the new added auction
        // set highest bid to its initial price since no bidders yet
        auctionList = (newAuction, newAuction.price) :: auctionList
      case HighestBidChanged(auction, highestBid)=>
        context.log.info(s"Ebay received notification for a change in the highest bid of auction: ${auction.item} with a new highest bid of ${highestBid}")
        // highest Bid for some auction has changed, update the highest bid for that auction
        auctionList = auctionList.filterNot((auc, highestBid)=> auc.item.equals(auction.item))
        auctionList = (auction, highestBid) :: auctionList
    }
    Behaviors.same
  }

object AuctionActor {

  // auctionActor state members
  var AuctionItem: String=""
  var initialPrice: Int=0
  var bids: List[Bid] = List[Bid]()
  // map to hold all the ActorRef's of the bidders created (bidder.bankAccount -> bidder Actor ref)
  val biddersActorRefs : scala.collection.mutable.Map[Int, ActorRef[BidSys]] = scala.collection.mutable.Map[Int, ActorRef[BidSys]]()


  // auctionActor messages
  case class AddBid(bid: Bid, bidderActor: ActorRef[BidSys]) extends BidSys
  case class RemoveBid(bid: Bid) extends BidSys
  case class WinnerResponse(payed: Boolean, auction: Auction) extends BidSys
  case class ReBidResponse(decision: Boolean, newBid: Int, bidder: Bidder) extends BidSys
  case class SendRebids(rebids: List[Bid]) extends BidSys

  private case object Timeout extends BidSys
  private case object TimerKey


  def apply(auction: Auction, ebay: ActorRef[BidSys]): Behavior[BidSys] = {
    Behaviors.setup { context =>
      context.log.info(s"inside auction actor -- auctionActor for ${auction.item} spawned")
      AuctionItem=auction.item
      initialPrice=auction.price
      // get the availability time of the auction
      val availability: FiniteDuration = auction.availabilityTime.minute
      // a behaviour that will schedule a message to self when a timer expires which i choose to implement the availability of the auction
      Behaviors.withTimers(timers => new AuctionHelper(timers, availability, auction, ebay).idle())
    }
  }


  class AuctionHelper(timers: TimerScheduler[BidSys],availability: FiniteDuration, auction: Auction, ebay: ActorRef[BidSys]) {
    import AuctionActor._

    def idle(): Behavior[BidSys] = {
      Behaviors.setup { context =>
        timers.startSingleTimer(TimerKey, Timeout, availability)
        active()
      }
    }

    def active(): Behavior[BidSys] = {
      Behaviors.receive[BidSys] { (context, message) =>
        message match {
          case Timeout =>
            // this message will be sent to self when the availability time of the auction expires
            context.log.info(s"inside auction actor -- Auction ${auction.item} expired")
            // Auction closed notify winning bidder
            if(bids.isEmpty){
              context.log.info(s"auction ${auction.item} expired before anyone bids on it")
            }else{
              // fetching the winner's actor (bidder with highest bid)
              val winnerBidderActor = biddersActorRefs(bids.head.bidder.bankAccount)
              // asking the winner to pay
              winnerBidderActor ! BidderActor.RequestToPay(auction, context.self)
            }
            Behaviors.same
          case AddBid(bid: Bid, bidderActor: ActorRef[BidSys]) =>
            context.log.info(s"bid of ${bid.biddingPrice} from ${bid.bidder.username} received and added for auction ${bid.auction.item} - inside auctionActor")
            // add bidder Actor to bidActor map (bidders are identified by their unique bank account number)
            biddersActorRefs += (bid.bidder.bankAccount -> bidderActor)

            if (bids.isEmpty) {
              bids = bid :: bids
              ebay ! EbayActor.HighestBidChanged(auction, bid.biddingPrice)
            }else{
              // get the current highest bid
              val highestBid = bids.head.biddingPrice
              // add the new bid
              bids = bid :: bids
              // sort the list of bids in descending order
              bids = bids.sortWith(_.biddingPrice>_.biddingPrice)
              if(bids.head.biddingPrice > highestBid) {
                // new highest bid => notify Ebay
                ebay ! EbayActor.HighestBidChanged(auction, bid.biddingPrice)
                // Notify all other bidders in map about new top bid
                // TODO: delegating this lengthy task to an ephemeral child actor (application of Ask pattern)
                val childNotifier = context.spawnAnonymous(NewTopBidNotifier(biddersActorRefs))
                childNotifier ! NewTopBidNotifier.NotifyBidders(bid)
              }
            }
            Behaviors.same
          case WinnerResponse(payed: Boolean, auction: Auction) =>
            if(payed){
              context.log.info(s"bidder ${bids.head.bidder.username} won with bid of: ${bids.head.biddingPrice} and payed for the auction: ${auction.item} before deadline")
            }else{
              // winning bidder didn't pay for auction
              context.log.info(s"bidder's ${bids.head.bidder.username} payment was not received for auction ${auction.item} ... asking all other bidders if they wanna rebid")
              // notify all other bidders if they wanna rebid
              // TODO: collect and aggregate all of the bidder's answers and new bids in the aggregator actor (application of aggregator pattern)
              // spawn a response aggregator to collect all the responses from the bidders
              val aggregator = context.spawn(ReBidResponseAggregator(auction, context.self, bids.length), "Processing")
              // send rebid requests to all bidders with a reply to set to the aggregator
              for ((bidderBankAccount, bidderActor) <- biddersActorRefs if bidderBankAccount != bids.head.bidder.bankAccount) {
                context.log.info("inside the loop")
                // notify this bidder and tell it to respond to the aggregator
                bidderActor ! BidderActor.ReBidRequest(auction, bids.head.biddingPrice, aggregator)
              }
            }
            Behaviors.same
          case SendRebids(rebids: List[Bid]) =>
            context.log.info(s"list of ReBids received and for auction ${auction.item}")
            // update the list of bidders with the removed bidders who refused to rebid
            bids = rebids
            // also update the Bidder actors map
            val bankAccounts = bids.map(_.bidder.bankAccount)
            biddersActorRefs.filterInPlace((bankAccount, bidderActor) => bankAccounts.contains(bankAccount))
            context.log.info(s"removed bidders who refused to reBid for ${auction.item}")
            // sort bids in descending order and inform Ebay of possible change in highest bid
            bids = bids.sortWith(_.biddingPrice>_.biddingPrice)
            ebay ! EbayActor.HighestBidChanged(auction, bids.head.biddingPrice)
            Behaviors.same
          case RemoveBid(bid: Bid) =>
            // TODO: Remove Bid
            Behaviors.same
        }
      }
    }
  }
}

object NewTopBidNotifier:

  case class NotifyBidders(highestBid: Bid) extends BidSys

  def apply(bidders: scala.collection.mutable.Map[Int, ActorRef[BidSys]]): Behavior[BidSys] =
    Behaviors.receive { (context, message) =>
      message match
        case NotifyBidders(highestBid: Bid) =>
          context.log.info(s"NewBidNotifier ephemeral child received a request to notify all bidders for auction ${highestBid.auction.item} of a new top bid...")
          for ((bidderBankAccount, bidderActor) <- bidders) {
            // notify this current bidder
            bidderActor ! BidderActor.NotifyNewTopBid(highestBid.biddingPrice, highestBid.auction)
          }
      Behaviors.stopped
    }

object ReBidResponseAggregator:

  // keep track of whether all the requested bidders has responded
  var receivedResponses: Int = 0
  // number of expected responses
  var expectedResponses: Int= 0
  var received: Int = 0
  // aggregate the bidders who accepted to rebid in this list to return to the auction
  // save only their bank account number which can uniquely identify them
  var reBidders: List[Bid]= List[Bid]()

  def apply(auction: Auction ,auctionFactory: ActorRef[BidSys], expected: Int): Behavior[BidSys] = {
    expectedResponses = expected
    receiveResponses(auction, auctionFactory)
  }

  def receiveResponses(auction: Auction, auctionActor: ActorRef[BidSys]): Behavior[BidSys] = Behaviors.receive { (context, message) =>
    message match
      case AuctionActor.ReBidResponse(decision: Boolean, newBid: Int, bidder: Bidder) =>
        // bidder responded
        received = received + 1
        if(decision){
          // bidder accepted to rebid
          context.log.info(s"bidder ${bidder.username} accepted to reBid for auction ${auction.item}")
          // create a an updated Bid object with the new bidding price
          val updatedBid: Bid = Bid(bidder, newBid , auction)
          // add the bidder to the list of bidders whom accepted to rebid
          reBidders= updatedBid :: reBidders
        }else{
          // bidder refused to rebid
          context.log.info(s"bidder ${bidder.username} refused to reBid for auction ${auction.item}")
        }
    checkCompleteProcess(auction, auctionActor)
  }

  // check if all responses from all requested bidders are received
  def checkCompleteProcess(auction: Auction, auctionActor: ActorRef[BidSys]): Behavior[BidSys] = Behaviors.setup {
    context =>
      if received == expectedResponses then
        context.log.info(s"All bidders responded to the reBid request from auction ${auction} ...")
        // all the bidders responded. inform the auction by sending the list of bidders who accepted with updated bidding price
        auctionActor ! AuctionActor.SendRebids(reBidders)
        Behaviors.stopped
      else
        receiveResponses(auction, auctionActor)
  }

object AuctionFactoryActor :

  case class AddAuction(auction: Auction, replyTo: ActorRef[BidSys]) extends BidSys
  case class RemoveAuction(auction: Auction) extends BidSys
  case class AddBid(bid:Bid, replyTo: ActorRef[BidSys]) extends BidSys

  var publishedAuctions: List[Auction] = List[Auction]()
  val auctionActorRefs : scala.collection.mutable.Map[String, ActorRef[BidSys]] = scala.collection.mutable.Map[String, ActorRef[BidSys]]()

  def apply(ebay: ActorRef[BidSys]): Behavior[BidSys] = Behaviors.receive { (context, message) =>
    context.log.info("The auctionFactory received a message!")

    message match
      case AddAuction(newAuction: Auction, replyTo: ActorRef[BidSys])  =>
        // spawn the new auction actor
        val newAuctionActor = context.spawnAnonymous(AuctionActor(newAuction, ebay))
        // inform the seller that it is added
        replyTo ! AuctionPublished(newAuction)
        // update the auction data base with the new auction
        publishedAuctions = newAuction :: publishedAuctions
        auctionActorRefs += (newAuction.item -> newAuctionActor)
        // notifying ebay of the new added auction
        ebay ! EbayActor.NewAuctionAdded(newAuction)
        // context.log.info("Hi! I am an actor that keeps a total sum! Send me a number to increase/decrease my sum!")
      case AddBid(bid:Bid, replyTo: ActorRef[BidSys])  =>
        // fetching the auction actor responsible for the auction the bidder is bidding for from the map
        val auctionActor = auctionActorRefs(bid.auction.item)
        // sending a request to this auction's auction actor to add the bid
        auctionActor ! AuctionActor.AddBid(bid, replyTo)

    Behaviors.same
  }

object BiddingSystemActor {

  def apply(): Behavior[Any] =
    Behaviors.setup { context =>
      // spawn an ebay actor
      val ebay = context.spawn(EbayActor(), "Ebay")
      // spawn an auctionFactory
      val auctionFactory = context.spawn(AuctionFactoryActor(ebay), "auctionFactory")
      // spawn a test seller
      val seller1 = context.spawn(SellerActor("seller 1", auctionFactory), "seller1")
      val seller2 = context.spawn(SellerActor("seller 2", auctionFactory), "seller2")
      // create a test auction to publish
      val testAuction1= Auction("item1", 100, List[Bid](), 2, 0)
      val testAuction2= Auction("item2", 200, List[Bid](), 1, 0)
      val testAuction3= Auction("item3", 400, List[Bid](), 3, 0)
      val testAuction4= Auction("item4", 350, List[Bid](), 3, 0)
      // seller 1 publishes an auction
      seller1 ! PublishAuction(testAuction1)
      seller1 ! PublishAuction(testAuction2)
      seller2 ! PublishAuction(testAuction4)
      Thread.sleep(5000)
      // create test bidder
      val bidder1 = Bidder("malek", 12146536)
      val bidder1Actor = context.spawn(BidderActor(auctionFactory, ebay, bidder1), "bidder1")
      bidder1Actor ! BidderActor.MakeBid()
      seller1 ! PublishAuction(testAuction3)
      bidder1Actor ! BidderActor.MakeBid()
      //val auctionRef1 = context.spawn(AuctionActor(testAction), "auction1")
      // auctionRef1 ! AuctionActor.AddBid("bidder 1")
      // auctionRef1 ! AuctionActor.AddBid("bidder 2")
      Behaviors.empty
    }

}

object biddingSystem extends App {
  val system: ActorSystem[Any] = ActorSystem(BiddingSystemActor(), "bidSYS")
  Thread.sleep(1000000)
  system.terminate()
}
