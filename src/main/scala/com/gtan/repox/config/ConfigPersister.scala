package com.gtan.repox.config

import akka.actor.{ActorRef, ActorLogging}
import akka.persistence.{PersistentActor, RecoveryCompleted}
import com.gtan.repox.admin.{RepoVO, ProxyServer}
import com.gtan.repox.{Immediate404Rule, Repo, Repox, RequestQueueMaster}
import com.ning.http.client.{ProxyServer => JProxyServer}
import io.undertow.util.StatusCodes

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

trait Cmd {
  def transform(old: Config): Config
}

object ConfigPersister extends RepoPersister {



  case class NewOrUpdateProxy(id: Long, proxy: ProxyServer) extends Cmd {
    override def transform(old: Config) = {
      val oldProxies = old.proxies
      old.copy(proxies = oldProxies.map {
        case ProxyServer(proxy.id, _, _, _, _) => proxy
        case p => p
      })
    }
  }

  case class DeleteProxy(id: Long) extends Cmd {
    override def transform(old: Config) = {
      val oldProxies = old.proxies
      val oldProxyUsage = old.proxyUsage
      old.copy(
        proxies = oldProxies.filterNot(_.id == id),
        proxyUsage = oldProxyUsage.filterNot { case (repo, proxy) => proxy.id == id}
      )
    }
  }

  case class RepoUseProxy(repo: Repo, proxy: Option[ProxyServer]) extends Cmd {
    override def transform(old: Config) = {
      val oldProxyUsage = old.proxyUsage
      old.copy(proxyUsage = proxy match {
        case Some(p) => oldProxyUsage.updated(repo, p)
        case None =>
          oldProxyUsage - repo
      })
    }
  }

  case class NewImmediate404Rule(rule: Immediate404Rule) extends Cmd {
    override def transform(old: Config) = {
      val oldRules = old.immediate404Rules
      old.copy(immediate404Rules = oldRules :+ rule)
    }
  }

  case class SetConnectionTimeout(d: Duration) extends Cmd {
    override def transform(old: Config) = {
      old.copy(connectionTimeout = d)
    }
  }

  case class SetConnectionIdleTimeout(d: Duration) extends Cmd {
    override def transform(old: Config) = {
      old.copy(connectionIdleTimeout = d)
    }
  }
  case class SetMainClientMaxConnections(m: Int) extends Cmd {
    override def transform(old: Config) = {
      old.copy(mainClientMaxConnections = m)
    }
  }
  case class SetMainClientMaxConnectionsPerHost(m: Int) extends Cmd {
    override def transform(old: Config) = {
      old.copy(mainClientMaxConnectionsPerHost = m)
    }
  }
  case class SetProxyClientMaxConnections(m: Int) extends Cmd {
    override def transform(old: Config) = {
      old.copy(proxyClientMaxConnections = m)
    }
  }
  case class SetProxyClientMaxConnectionsPerHost(m: Int) extends Cmd {
    override def transform(old: Config) = {
      old.copy(proxyClientMaxConnectionsPerHost = m)
    }
  }

  trait Evt

  case class ConfigChanged(config: Config, cmd: Cmd) extends Evt

  case object UseDefault extends Evt

}

class ConfigPersister extends PersistentActor with ActorLogging {

  import ConfigPersister._
  override def persistenceId = "Config"

  var config: Config = _

  def onConfigSaved(sender: ActorRef, c: ConfigChanged) = {
    log.debug(s"event: $c")
    config = c.config
    Config.set(config)
    sender ! StatusCodes.OK
  }

  val receiveCommand: Receive = {
    case cmd: Cmd =>
      persist(ConfigChanged(cmd.transform(config), cmd))(onConfigSaved(sender(), _))
    case UseDefault =>
      persist(UseDefault) { _ =>
        config = Config.default
        Config.set(config).foreach { _ =>
          Repox.requestQueueMaster ! RequestQueueMaster.ConfigLoaded
        }
      }
  }

  val receiveRecover: Receive = {
    case ConfigChanged(data, cmd) =>
      config = data

    case UseDefault =>
      config = Config.default

    case RecoveryCompleted =>
      if (config == null) {
        // no config history, save default data as snapshot
        self ! UseDefault
      } else {
        Config.set(config).foreach { _ =>
          Repox.requestQueueMaster ! RequestQueueMaster.ConfigLoaded
        }
      }
  }
}
