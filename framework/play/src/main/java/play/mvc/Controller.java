package play.mvc;

import play.api.*;

import play.mvc.Http.*;
import play.mvc.Result.*;

public abstract class Controller {
    
    public static Request request() {
        return Http.Context.current().request();
    }
    
    public static Result ok(Content content) {
        return new Ok(content);
    }
    
    public static Result ok(String content) {
        return ok(content, "text/html");
    }
    
    public static Result ok(String content, String contentType) {
        return new Ok(content, contentType);
    }
    
    public static Result notFound(Content content) {
        return new NotFound(content);
    }
    
    public static Result notFound(String content) {
        return notFound(content, "text/html");
    }
    
    public static Result notFound(String content, String contentType) {
        return new NotFound(content, contentType);
    }
    
    public static Result forbidden(Content content) {
        return new Forbidden(content);
    }
    
    public static Result forbidden(String content) {
        return forbidden(content, "text/html");
    }
    
    public static Result forbidden(String content, String contentType) {
        return new Forbidden(content, contentType);
    }
    
    public static Result badRequest(Content content) {
        return new BadRequest(content);
    }
    
    public static Result badRequest(String content) {
        return badRequest(content, "text/html");
    }
    
    public static Result badRequest(String content, String contentType) {
        return new BadRequest(content, contentType);
    }
    
    public static Result redirect(String url) {
        return new Redirect(url);
    }
    
    public static Result redirect(play.api.mvc.Call call) {
        return new Redirect(call.url());
    }
    
}