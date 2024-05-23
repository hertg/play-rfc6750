package org.example;

import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

public class RootController extends Controller {

    public Result index(final Http.Request request) {
        return ok("hello world");
    }

}
