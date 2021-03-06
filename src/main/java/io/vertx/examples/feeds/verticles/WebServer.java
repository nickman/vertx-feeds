package io.vertx.examples.feeds.verticles;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.examples.feeds.dao.MongoDAO;
import io.vertx.examples.feeds.dao.RedisDAO;
import io.vertx.examples.feeds.handlers.UserContextHandler;
import io.vertx.examples.feeds.handlers.api.AuthenticationApi;
import io.vertx.examples.feeds.handlers.api.FeedsApi;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.Session;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CookieHandler;
import io.vertx.ext.web.handler.ErrorHandler;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.TemplateHandler;
import io.vertx.ext.web.sstore.LocalSessionStore;
import io.vertx.ext.web.sstore.SessionStore;
import io.vertx.ext.web.templ.HandlebarsTemplateEngine;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.ext.web.handler.sockjs.PermittedOptions;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.redis.RedisClient;

public class WebServer extends AbstractVerticle {

    private HttpServer server;

    private AuthenticationApi authApi;
    private FeedsApi feedsApi;
    private UserContextHandler userContextHandler;
    private MongoDAO mongo;
    private RedisDAO redis;
    private JsonObject config;

    @Override
    public void init(Vertx vertx, Context context) {
        super.init(vertx, context);
        config = context.config();
    }

    @Override
    public void start(Future<Void> future) {
        mongo = new MongoDAO(MongoClient.createShared(vertx, config.getJsonObject("mongo")));
        redis = new RedisDAO(RedisClient.create(vertx, config.getJsonObject("redis")));
        authApi = new AuthenticationApi(mongo);
        feedsApi = new FeedsApi(mongo, redis);
        redis.start(handler -> {
            server = vertx.createHttpServer(createOptions());
            server.requestHandler(createRouter()::accept);
            server.listen(result -> {
                if (result.succeeded()) {
                    future.complete();
                } else {
                    future.fail(result.cause());
                }
            });
        });
    }

    @Override
    public void stop(Future<Void> future) {
        if (server == null) {
            future.complete();
            return;
        }
        server.close(result -> {
            if (result.failed()) {
                future.fail(result.cause());
            } else {
                future.complete();
            }
        });
    }

    private HttpServerOptions createOptions() {
        HttpServerOptions options = new HttpServerOptions();
        options.setHost("localhost");
        options.setPort(9000);
        return options;
    }

    private Router createRouter() {
        Router router = Router.router(vertx);
        router.route().failureHandler(ErrorHandler.create(true)); /* display exception details */

        /* Static resources */
        staticHandler(router);

        /* Session / cookies for users */
        router.route().handler(CookieHandler.create());
        SessionStore sessionStore = LocalSessionStore.create(vertx);
        SessionHandler sessionHandler = SessionHandler.create(sessionStore);
        router.route().handler(sessionHandler);
        userContextHandler = new UserContextHandler(mongo);

        /* Dynamic pages */
        dynamicPages(router);

        /* API */
        router.mountSubRouter("/api", apiRouter(userContextHandler));

        /* SockJS / EventBus */
        router.route("/eventbus/*").handler(eventBusHandler());

        return router;
    }

    private SockJSHandler eventBusHandler() {
        SockJSHandler handler = SockJSHandler.create(vertx);
        BridgeOptions options = new BridgeOptions();
        PermittedOptions permitted = new PermittedOptions(); // allow everything, we don't care for the demo
        options.addOutboundPermitted(permitted);
        handler.bridge(options);
        return handler;
    }

    private Router staticHandler(Router router) {
        StaticHandler staticHandler = StaticHandler.create();
        staticHandler.setCachingEnabled(false);
        router.route("/assets/*").handler(staticHandler);
        return router;
    }

    private Router dynamicPages(Router router) {
        HandlebarsTemplateEngine hbsEngine = HandlebarsTemplateEngine.create();
        hbsEngine.setMaxCacheSize(0); // no cache since we wan't hot-reload for templates
        TemplateHandler templateHandler = TemplateHandler.create(hbsEngine);
        router.get("/private/*").handler(userContextHandler::fromSession);
        router.getWithRegex(".+\\.hbs").handler(context -> {
            final Session session = context.session();
            context.data().put("userLogin", session.get("login")); /* in order to greet him */
            context.data().put("accessToken", session.get("accessToken")); /* for api calls */
            context.next();
        });
        router.getWithRegex(".+\\.hbs").handler(templateHandler);
        return router;
    }

    private Router apiRouter(UserContextHandler userContextHandler) {
        /*
         * TODO : provide authentication through the AuthService / AuthProvider instead of a custom api handler
         * TODO : every page except login must be private
         * TODO : use FormLoginHandler for the actual login form
         * TODO : use RedirectAuthHandler for "/private"
         * 
         * router.route().handler(CookieHandler.create());
         * router.route().handler(SessionHandler.create(LocalSessionStore.create(vertx)));
         * 
         * AuthProvider provider =
         */
        Router router = Router.router(vertx);
        router.route().consumes("application/json");
        router.route().produces("application/json");
        router.route().handler(BodyHandler.create());
        router.route().handler(context -> {
            context.response().headers().add("Content-Type", "application/json");
            context.next();
        });

        /* login / user-related stuff : no token needed */
        router.post("/register").handler(authApi::register);
        router.post("/login").handler(authApi::login);
        router.post("/logout").handler(authApi::logout);

        /* API to deal with feeds : token required */
        router.route("/feeds*").handler(userContextHandler::fromApiToken);
        router.post("/feeds").handler(feedsApi::create);
        router.get("/feeds").handler(feedsApi::list);
        router.get("/feeds/:feedId").handler(feedsApi::retrieve);
        router.put("/feeds/:feedId").handler(feedsApi::update);
        router.get("/feeds/:feedId/entries").handler(feedsApi::entries);
        router.delete("/feeds/:feedId").handler(feedsApi::delete);

        return router;
    }
}
