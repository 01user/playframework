/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package play.api.inject
package guice

import com.google.inject._
import play.api.inject.{ Module => PlayModule, Binding => PlayBinding }
import com.google.inject.util.Providers
import play.api._

class GuiceLoadException(message: String) extends RuntimeException(message)

/**
 * An ApplicationLoader that uses guice to bootstrap the application.
 */
class GuiceApplicationLoader extends ApplicationLoader {

  def load(context: ApplicationLoader.Context): Application = {

    val env = context.environment

    // Load global
    val global = GlobalSettings(context.initialConfiguration, env)

    // Create the final configuration
    // todo - abstract this logic out into something pluggable, with the default delegating to global
    val configuration = global.onLoadConfig(context.initialConfiguration, env.rootPath, env.classLoader, env.mode)

    Logger.configure(env.rootPath, configuration, env.mode)

    val modules = guiced(Seq(
      BindingKey(classOf[GlobalSettings]) to global,
      BindingKey(classOf[OptionalSourceMapper]) to new OptionalSourceMapper(context.sourceMapper)
    )) +: Modules.locate(env, configuration)

    val guiceModules = modules.map {
      case playModule: PlayModule => guiced(playModule.bindings(env, configuration))
      case guiceModule: Module => guiceModule
      case unknown => throw new PlayException(
        "Unknown module type",
        s"Module [$unknown] is not a Play module or a Guice module"
      )
    }

    import scala.collection.JavaConverters._

    // load play module bindings
    val injector = Guice.createInjector(guiceModules.asJavaCollection)
    injector.getInstance(classOf[Application])
  }

  private def guiced(bindings: Seq[PlayBinding[_]]): AbstractModule = {
    new AbstractModule {
      def configure(): Unit = {
        for (b <- bindings) {
          val binding = b.asInstanceOf[PlayBinding[Any]]
          val builder = bind(bindingKeyToGuice(binding.key))
          binding.target.foreach {
            case ProviderTarget(provider) => builder.toProvider(Providers.guicify(provider))
            case ProviderConstructionTarget(provider) => builder.toProvider(provider)
            case ConstructionTarget(implementation) => builder.to(implementation)
            case BindingKeyTarget(key) => builder.to(bindingKeyToGuice(key))
          }
          (binding.scope, binding.eager) match {
            case (Some(scope), false) => builder.in(scope)
            case (None, true) => builder.asEagerSingleton()
            case (Some(scope), true) => throw new GuiceLoadException("A binding must either declare a scope or be eager: " + binding)
            case _ => // do nothing
          }
        }
      }
    }
  }

  private def bindingKeyToGuice[T](key: BindingKey[T]): Key[T] = {
    key.qualifier match {
      case Some(QualifierInstance(instance)) => Key.get(key.clazz, instance)
      case Some(QualifierClass(clazz)) => Key.get(key.clazz, clazz)
      case None => Key.get(key.clazz)
    }
  }
}
