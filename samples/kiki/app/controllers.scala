package controllers

import play.api.mvc._
import play.api.mvc.Results._

object Actions {
    
    def Secured[A](predicate:Request[A]=>Boolean)(action:Action[A]):Action[A] = Action(action.parser, ctx => {
        if(predicate(ctx)) {
            action(ctx)
        } else {
            Forbidden
        }
    })
    
    def Secured[A](predicate: =>Boolean)(action:Action[A]):Action[A] = Secured((_:Request[A]) => predicate)(action)
    
    val cache = scala.collection.mutable.HashMap.empty[String,Result] 
    
    def Cached[A](args: Any*)(action:Action[A]) = Action[A](action.parser, ctx => {
        val key = args.mkString
        
        cache.get(key).getOrElse {
            val r = action(ctx)
            cache.put(key, r)
            r
        }
    })
    
}

object Blocking extends Controller {

    val waited = play.core.Iteratee.Promise[Int]()

    def unblockEveryone(status:Int) = Action { ctx =>
        waited.redeem(status) 
        Ok
    }

    def waitForUnblock = Action {
        AsyncResult(waited.map{ status => println("status"); Status(status)})

    }

}

object TOTO {
    
    lazy val a = System.currentTimeMillis
    
} 

object Application extends Controller {
    
    override def Action[A](bodyParser:BodyParser[A],block:Request[A]=>Result) = super.Action(bodyParser,ctx => {
        println("Request for Application controller")
        block(ctx)
    }) 

    
    def coco = Action {
        NotFound("oops")
    }
    
    import play.core.Iteratee._
    
    def index = Action {
        Ok(html.views.index("World " + TOTO.a)).flashing("success" -> "DONE!")
    }
    
    def websocketTest = Action {
        Ok(html.views.sockets())
    }
    
    def moreSockets = Action {
        Ok(html.views.moreSockets())
    }
    
    def socketEchoReversed = Action {
        SocketResult[String]{ (in,out) => 
            out <<: in.map {
                case El("") => EOF
                case o => o.map(_.reverse)
            }
        }
    }
    
    val specialTemplates = Map(
        "home"  -> html.views.pages.home.f,
        "about" -> html.views.pages.about.f
    )
    
    def page(name:String) = Action {
        Ok(specialTemplates.get(name).getOrElse(html.views.pages.page.f)(name, "Dummy content"))
    }
    
    def list(page:Int, sort:String) = {
        
        val p = if(page > 0) page else 1
        
        Actions.Secured(p != 42) { 
            Actions.Cached(p, sort) { 
                Action {
                    println("Listing page " + p + " using " + sort)
                    Ok(html.views.list(p, sort))
                }
            }
        } 
    }
    
    def goToJava = Action {
        Redirect(routes.JavaApplication.index)
    }
    
    def bindOptions(p1: Option[Int], p2: Option[Int]) = Action {
      Ok( """
        params: p1=%s, p2=%s
        reversed: %s
        reversed: %s
        reversed: %s
        reversed: %s
      """.format(
        p1, p2,
        routes.Application.bindOptions(None, None),
        routes.Application.bindOptions(Some(42), None),
        routes.Application.bindOptions(None, Some(42)),
        routes.Application.bindOptions(Some(42), Some(42))
      ))
    }

}
