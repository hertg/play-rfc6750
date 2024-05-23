package org.example;

import play.libs.typedmap.TypedKey;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

public class RootController extends Controller {

    private static final TypedKey<String> TOKEN_ATTR = Rfc6750Action.RAW_ACCESS_TOKEN().asJava();

    public Result index(final Http.Request request) {
        return ok("hello world");
    }

    public Result echoToken(final Http.Request request) {
        var token = request.attrs().getOptional(TOKEN_ATTR);
        return ok(token.orElse(""));
    }

}
