package notebook
package kernel.remote

import java.io.File

import akka.actor.{Actor, Props}
import notebook.kernel.pfork.ProcessFork

class VMManager(process: ProcessFork[RemoteProcess]) extends Actor {

  import VMManager._

  private[this] val router = context.actorOf(Props[Router])

  def receive = {
    case Start(key, location) =>
      router ! Router.Put(key, context.actorOf(Props(new SingleVM(process, location))))
    case Spawn(key, props) =>
      router.forward(Router.Forward(key, SingleVM.Spawn(props)))
    case Kill(key) =>
      router ! Router.Remove(key)
  }
}

object VMManager {

  case class Start(key: Any, location: File)

  case class Spawn(key: Any, props: Props)

  case class Kill(key: Any)

}