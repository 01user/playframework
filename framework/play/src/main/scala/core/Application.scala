package play.core

import play.api._
import play.api.mvc._

import play.core.logger._

import java.io._
import java.net._

class ApplicationClassLoader(parent:ClassLoader, urls:Array[URL] = Array.empty) extends URLClassLoader(urls, parent) {
    
    def loadClassParentLast(name:String) = try {
        findClass(name)
    } catch {
        case e => loadClass(name)
    }
    
}

case class Application(path:File, classloader:ApplicationClassLoader, sources:SourceMapper) {
    
    def actionFor(request:Request):Option[Action] = {
        import java.lang.reflect._
        
        try {
            classloader.loadClassParentLast("Routes").getDeclaredMethod("actionFor", classOf[Request]).invoke(null, request).asInstanceOf[Option[Action]]
        } catch {
            case e:InvocationTargetException if e.getTargetException.isInstanceOf[PlayException] => throw e.getTargetException
            case e:InvocationTargetException => {
                throw ExecutionException(e.getTargetException, sources.sourceFor(e.getTargetException))
            }
            case e => throw ExecutionException(e, sources.sourceFor(e))
        }
        
    }
    
    val configuration = Configuration.fromFile(new File(path, "conf/application.conf"))
    
    configuration.getInt("pool.size")
    
    val plugins = {
        
        import scalax.file._
        import scalax.io.Input.asInputConverter
        
        import scala.collection.JavaConverters._
        
        val PluginDeclaration = """([0-9_]+):(.*)""".r
        
        classloader.getResources("play.plugins").asScala.toList.distinct.map { plugins =>
            plugins.asInput.slurpString.split("\n").map(_.trim).filterNot(_.isEmpty).collect {
                case PluginDeclaration(priority, className) if className.endsWith("$") => {
                    Integer.parseInt(priority) -> classloader.loadClassParentLast(className).getDeclaredField("MODULE$").get(null).asInstanceOf[Plugin]
                }
                case PluginDeclaration(priority, className) => {
                    Integer.parseInt(priority) -> classloader.loadClassParentLast(className).newInstance.asInstanceOf[Plugin]
                }
            }
        }.flatten.toList.sortBy(_._1).map(_._2)
        
    }
    
}

trait SourceMapper {
    
    def sourceOf(className:String):Option[File]
    
    def sourceFor(e:Throwable):Option[(File,Int)] = {
        e.getStackTrace.find(element => sourceOf(element.getClassName).isDefined).map { interestingStackTrace =>
            sourceOf(interestingStackTrace.getClassName).get -> interestingStackTrace.getLineNumber
        }.map {
            case (source,line) => {
                play.templates.MaybeGeneratedSource.unapply(source).map { generatedSource =>
                    generatedSource.source.get -> generatedSource.mapLine(line)
                }.getOrElse(source -> line)
            }
        }
    }
    
}

case class NoSourceAvailable() extends SourceMapper {
    def sourceOf(className:String) = None
}

trait ApplicationProvider {
    def path:File
    def get:Either[PlayException,Application]
}

class StaticApplication(applicationPath:File) extends ApplicationProvider {
    val application = Application(applicationPath, new ApplicationClassLoader(classOf[StaticApplication].getClassLoader), NoSourceAvailable())
    
    Play.start(application)
    
    def get = Right(application)
    def path = applicationPath
}

abstract class ReloadableApplication(applicationPath:File) extends ApplicationProvider {
    
    Logger.log("Running the application from SBT, auto-reloading is enabled")
    
    var lastState:Either[PlayException,Application] = Left(PlayException("Not initialized", ""))
    
    def get = {
        
        reload.right.flatMap { maybeClassloader =>
            
            val maybeApplication:Option[Either[PlayException,Application]] = maybeClassloader.map { classloader =>
                try {
                    
                    val newApplication = Application(applicationPath, classloader, new SourceMapper {
                        def sourceOf(className:String) = findSource(className)
                    })
                    
                    Play.start(newApplication)
                    
                    Right(newApplication)
                } catch {
                    case e:PlayException => {
                        lastState = Left(e)
                        lastState
                    }
                    case e => {
                        lastState = Left(UnexpectedException(unexpected=Some(e)))
                        lastState
                    }
                }
            }
            
            maybeApplication.flatMap(_.right.toOption).foreach { app => 
                lastState = Right(app)
            }
            
            maybeApplication.getOrElse(lastState)
        }
    }
    def reload:Either[PlayException,Option[ApplicationClassLoader]]
    def path = applicationPath
    def findSource(className:String):Option[File]

}
