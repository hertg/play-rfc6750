package org.example;

import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

public class RootController extends Controller {

    public Result index(final Http.Request request) {
        return ok("hello world");
    }

    public Result echoToken(final Http.Request request) {
        var token = request.attrs().getOptional(Rfc6750Action.RAW_ACCESS_TOKEN().asJava());
        return ok(token.orElse(""));
    }

}
