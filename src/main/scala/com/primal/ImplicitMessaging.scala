package com.primal

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import java.util.UUID
import scala.concurrent.duration._

object Messaging {
  // Defines the "type" of component in a message (e.g. MessageForwarder)
  case class ComponentType(componentType: String)
  val unknownComponentType = ComponentType("UnknownComponentType")
  
  // Defines the identity of the given component (e.g. /path/to/MessageForwarder)
  case class ComponentId(componentId: String)
  val unknownComponentId = ComponentId("UnknownComponentId")
  
  // Defines the type of message being sent (e.g. SendEmail)
  case class MessageType(messageType: String)
  val unknownMessageType = MessageType("UnknownMessageType")
  
  // Defines the work identifier that this message is part of
  case class WorkId(workId: String)
  val unknownWorkId = WorkId("UnknownWorkId")
  def createWorkId(): WorkId = WorkId(UUID.randomUUID().toString)
  
  // Defines the sequence number of this message within the workId
  case class MessageNum(messageNum: Int) {
    def increment: MessageNum = MessageNum(messageNum + 1)
  }
  
  // The version of the envelope protocol
  case class EnvelopeVersion(version: Int = 1)
  
  // We shouldn't have to supply this, generally, just pull it in from
  // the implicit scope
  implicit val envelopeVersion = EnvelopeVersion()

  // Rips off the unneeded bits from the Actor path in order to
  // create a unique Id
  def makeAnIdForActorRef(path: ActorPath): String = path.elements.mkString("/", "/", "")

  // This is the ultimate class in which we're interested.  It contains all of
  // the meta-information we need in order to see what's what
  case class Envelope(fromComponentType: ComponentType,
                      fromComponentId: ComponentId,
                      toComponentId: ComponentId,
                      messageType: MessageType,
                      workId: WorkId,
                      messageNum: MessageNum,
                      version: EnvelopeVersion,
                      createdTimeStamp: Long,
                      message: Any)

  // An implicit conversion makes things easy for us; we can convert from an Any
  // to an Envelope with our implicit
  trait EnvelopeImplicits {
    import scala.language.implicitConversions
  
    implicit def any2Envelope(a: Any)
                             (implicit fromComponentType: ComponentType,
                                       fromComponentId: ComponentId,
                                       workId: WorkId,
                                       messageNum: MessageNum,
                                       version: EnvelopeVersion): Envelope = 
      Envelope(fromComponentType,
               fromComponentId,
               unknownComponentId,
               MessageType(a.getClass.getSimpleName),
               workId,
               messageNum,
               version,
               System.currentTimeMillis,
               a)
               
  }

  // Here we create an implicit class that extends the functionality of the
  // ActorRef, which will provide the hook we need in order to convert from the
  // Any to the Envelope that holds all of our interesting information
  trait ActorRefImplicits {
    implicit class PimpedActorRef(ref: ActorRef) {
      def emit(envelope: Envelope)(implicit sender: ActorRef = Actor.noSender): Unit = {
        ref.tell(envelope.copy(
            toComponentId = ComponentId(makeAnIdForActorRef(ref.path)),
            messageNum = envelope.messageNum.increment
          ), sender)
      }
  
      def emitForward(envelope: Envelope)(implicit context: ActorContext): Unit = {
        ref.forward(envelope.copy(
            toComponentId = ComponentId(makeAnIdForActorRef(ref.path)),
            messageNum = envelope.messageNum.increment
          ))
      }
    }
  }

  // Here we go.  The EnvelopingActor base class binds all of the bits an pieces
  // together into a decent whole.  Derivation implementations are thus easy.
  trait EnvelopingActor extends Actor with EnvelopeImplicits with ActorRefImplicits {
    implicit val myComponentType: ComponentType = ComponentType(getClass.getSimpleName)
    implicit val myComponentId: ComponentId = ComponentId(makeAnIdForActorRef(self.path))

    private var currentWorkIdVar: WorkId = unknownWorkId
    implicit def workId: WorkId = currentWorkIdVar

    private var currentMessageNumVar: MessageNum = MessageNum(-1)
    implicit def messageNum: MessageNum = currentMessageNumVar

    def derivedReceive: Receive

    def derivedReceiveWrapper(wrapped: Receive): Receive = {
      case Envelope(_, _, _, _, workId, messageNum, _, _, message) =>
        currentWorkIdVar = workId
        currentMessageNumVar = messageNum
        wrapped(message)
      case message =>
        currentWorkIdVar = createWorkId()
        currentMessageNumVar = MessageNum(-1)
        wrapped(message)
    }

    final def receive = derivedReceiveWrapper(derivedReceive)
  }
}

object ImplicitMessaging extends App {
  import Messaging._

  val system = ActorSystem("ImplicitMessaging")
  implicit val ec = system.dispatcher
  implicit val askTimeout = Timeout(5.seconds)

  // Done.  This class extends the EnvelopingActor and all it has to do is
  // implement derivedReceive instead of receive and change ! to emit.
  class MyActor extends EnvelopingActor {
    def derivedReceive = {
      case msg =>
        sender emit "Hello"
    }
  }

  // Let's send it a simple non-envelope message and see what happens
  val a = system.actorOf(Props(new MyActor))
  a ? "Ignored" onSuccess {
    case msg =>
      println(s"\n$msg\n")
  }

  // Now let's send it an existing envelope to see what happens
  val env = Envelope(ComponentType("MyActor"),
                     ComponentId(makeAnIdForActorRef(a.path)),
                     ComponentId("Main:1"),
                     MessageType("String"),
                     createWorkId(),
                     MessageNum(0),
                     envelopeVersion,
                     System.currentTimeMillis,
                     "Hello")
  a ? env onSuccess {
    case msg =>
      println(s"\n$msg\n")
      system.shutdown()
  }
}
