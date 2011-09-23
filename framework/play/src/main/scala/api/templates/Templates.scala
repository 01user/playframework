package play.api.templates

import play.api._
import play.templates._

case class Html(text:String) extends Appendable[Html] with Content {
    val buffer = new StringBuilder(text)

    def +(other:Html) = {
        buffer.append(other.buffer)
        this
    }
    override def toString = buffer.toString
    
    def contentType = "text/html"
    def body = toString
    
}

object Html {
    def empty = Html("")
}

object HtmlFormat extends Format[Html] {
    def raw(text:String) = Html(text)
    def escape(text:String) = Html(text.replace("<","&lt;"))
}

object PlayMagic {
    
    implicit def javaOptionToScala[T](x:play.libs.F.Option[T]):Option[T] = {
        x match {
            case x:play.libs.F.Some[T] => Some(x.get)
            case x:play.libs.F.None[T] => None
        }
    }
    
}