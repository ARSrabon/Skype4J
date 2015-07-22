package com.samczsun.skype4j.internal;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.samczsun.skype4j.ConnectionBuilder;
import com.samczsun.skype4j.Skype;
import com.samczsun.skype4j.StreamUtils;
import com.samczsun.skype4j.chat.Chat;
import com.samczsun.skype4j.events.EventDispatcher;
import com.samczsun.skype4j.events.chat.ChatJoinedEvent;
import com.samczsun.skype4j.events.chat.DisconnectedEvent;
import com.samczsun.skype4j.exceptions.ConnectionException;
import com.samczsun.skype4j.exceptions.InvalidCredentialsException;
import com.samczsun.skype4j.exceptions.ParseException;
import org.jsoup.Connection.Method;
import org.jsoup.Connection.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SkypeImpl extends Skype {
    private static final String LOGIN_URL = "https://login.skype.com/login?client_id=578134&redirect_uri=https%3A%2F%2Fweb.skype.com";
    private static final String PING_URL = "https://web.skype.com/api/v1/session-ping";
    private static final String TOKEN_AUTH_URL = "https://api.asm.skype.com/v1/skypetokenauth";
    private static final String LOGOUT_URL = "https://login.skype.com/logout?client_id=578134&redirect_uri=https%3A%2F%2Fweb.skype.com&intsrc=client-_-webapp-_-production-_-go-signin";
    private static final String ENDPOINTS_URL = "https://client-s.gateway.messenger.live.com/v1/users/ME/endpoints";
    // The endpoints below all depend on the cloud the user is in
    private static final String SUBSCRIPTIONS_URL = "https://%sclient-s.gateway.messenger.live.com/v1/users/ME/endpoints/SELF/subscriptions";
    private static final String MESSAGINGSERVICE_URL = "https://%sclient-s.gateway.messenger.live.com/v1/users/ME/endpoints/%s/presenceDocs/messagingService";
    private static final String POLL_URL = "https://%sclient-s.gateway.messenger.live.com/v1/users/ME/endpoints/SELF/subscriptions/0/poll";

    private final AtomicBoolean loggedIn = new AtomicBoolean(false);
    private final String username;
    private final String password;

    private EventDispatcher eventDispatcher;
    private String skypeToken;
    private String registrationToken;
    private String endpointId;
    private Map<String, String> cookies;

    private String cloud = "";

    private Thread sessionKeepaliveThread;
    private Thread pollThread;

    private final ExecutorService scheduler = Executors.newFixedThreadPool(16);
    private final Logger logger = Logger.getLogger("webskype");
    private final Map<String, Chat> allChats = new ConcurrentHashMap<>();

    public SkypeImpl(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public void subscribe() throws IOException {
        ConnectionBuilder builder = new ConnectionBuilder();
        builder.setUrl(withCloud(SUBSCRIPTIONS_URL));
        builder.setMethod("POST", true);
        builder.addHeader("RegistrationToken", registrationToken);
        builder.addHeader("Content-Type", "application/json");
        builder.setData(buildSubscriptionObject().toString());
        HttpURLConnection connection = builder.build();

        int code = connection.getResponseCode();
        if (code != 201) {
            throw generateException(connection);
        }

        builder.setUrl(withCloud(MESSAGINGSERVICE_URL, URLEncoder.encode(endpointId, "UTF-8")));
        builder.setMethod("PUT", true);
        builder.setData(buildRegistrationObject().toString());
        connection = builder.build();

        code = connection.getResponseCode();
        if (code != 200) {
            throw generateException(connection);
        }
        pollThread = new Thread(String.format("Skype-%s-PollThread", username)) {
            public void run() {
                ConnectionBuilder poll = new ConnectionBuilder();
                poll.setUrl(withCloud(POLL_URL));
                poll.setMethod("POST", true);
                poll.addHeader("RegistrationToken", registrationToken);
                poll.addHeader("Content-Type", "application/json");
                poll.setData("");
                main:
                while (loggedIn.get()) {
                    try {
                        HttpURLConnection c = poll.build();
                        AtomicInteger code = new AtomicInteger(0);
                        while (code.get() == 0) {
                            try {
                                code.set(c.getResponseCode());
                            } catch (SocketTimeoutException e) {
                                if (Thread.currentThread().isInterrupted()) {
                                    break main;
                                }
                            }
                        }

                        if (code.get() != 200) {
                            throw generateException(c);
                        }

                        InputStream read = c.getInputStream();
                        String json = StreamUtils.readFully(read);
                        if (!json.isEmpty()) {
                            final JsonObject message = JsonObject.readFrom(json);
                            if (!scheduler.isShutdown()) {
                                scheduler.execute(new Runnable() {
                                    public void run() {
                                        try {
                                            JsonArray arr = message.get("eventMessages").asArray();
                                            for (JsonValue elem : arr) {
                                                JsonObject eventObj = elem.asObject();
                                                String resourceType = eventObj.get("resourceType").asString();
                                                if (resourceType.equals("NewMessage")) {
                                                    JsonObject resource = eventObj.get("resource").asObject();
                                                    String messageType = resource.get("messagetype").asString();
                                                    MessageType type = MessageType.getByName(messageType);
                                                    type.handle(SkypeImpl.this, resource);
                                                } else if (resourceType.equalsIgnoreCase("EndpointPresence")) {
                                                } else if (resourceType.equalsIgnoreCase("UserPresence")) {
                                                } else if (resourceType.equalsIgnoreCase("ConversationUpdate")) { //Not sure what this does
                                                } else if (resourceType.equalsIgnoreCase("ThreadUpdate")) {
                                                    JsonObject resource = eventObj.get("resource").asObject();
                                                    String chatId = resource.get("id").asString();
                                                    Chat chat = getChat(chatId);
                                                    if (chat == null) {
                                                        chat = ChatImpl.createChat(SkypeImpl.this, chatId);
                                                        allChats.put(chatId, chat);
                                                        ChatJoinedEvent e = new ChatJoinedEvent(chat);
                                                        eventDispatcher.callEvent(e);
                                                    }
                                                } else {
                                                    logger.severe("Unhandled resourceType " + resourceType);
                                                    logger.severe(eventObj.toString());
                                                }
                                            }
                                        } catch (Exception e) {
                                            logger.log(Level.SEVERE, "Exception while handling message", e);
                                            logger.log(Level.SEVERE, message.toString());
                                        }
                                    }
                                });
                            }
                        }
                    } catch (IOException e) {
                        eventDispatcher.callEvent(new DisconnectedEvent(e));
                        loggedIn.set(false);
                    }
                }
            }
        };
        pollThread.start();
    }

    @Override
    public Chat getChat(String name) {
        return allChats.get(name);
    }

    @Override
    public Chat loadChat(String name) throws ConnectionException {
        if (!allChats.containsKey(name)) {
            Chat chat = ChatImpl.createChat(this, name);
            allChats.put(name, chat);
            return getChat(name);
        } else {
            throw new IllegalArgumentException("Chat already exists");
        }
    }

    @Override
    public Collection<Chat> getAllChats() {
        return Collections.unmodifiableCollection(this.allChats.values());
    }

    @Override
    public void logout() throws ConnectionException {
        ConnectionBuilder builder = new ConnectionBuilder();
        builder.setUrl(LOGOUT_URL);
        builder.addHeader("Cookies", serializeCookies(cookies));
        try {
            HttpURLConnection con = builder.build();
            if (con.getResponseCode() != 302) {
                throw generateException(con);
            }
            loggedIn.set(false);
            pollThread.interrupt();
            sessionKeepaliveThread.interrupt();
            scheduler.shutdownNow();
            while (!scheduler.isTerminated()) ;
        } catch (IOException e) {
            throw new ConnectionException("While logging out", e);
        }
    }

    public String getRegistrationToken() {
        return this.registrationToken;
    }

    public String getSkypeToken() {
        return this.skypeToken;
    }

    @Override
    public EventDispatcher getEventDispatcher() {
        return this.eventDispatcher;
    }

    public Logger getLogger() {
        return this.logger;
    }

    private Response postToLogin(String username, String password) throws ConnectionException {
        try {
            Map<String, String> data = new HashMap<>();
            Document loginDocument = Jsoup.connect(LOGIN_URL).get();
            Element loginForm = loginDocument.getElementById("loginForm");
            for (Element input : loginForm.getElementsByTag("input")) {
                data.put(input.attr("name"), input.attr("value"));
            }
            Date now = new Date();
            data.put("timezone_field", new SimpleDateFormat("XXX").format(now).replace(':', '|'));
            data.put("username", username);
            data.put("password", password);
            data.put("js_time", String.valueOf(now.getTime() / 1000));
            return Jsoup.connect(LOGIN_URL).data(data).method(Method.POST).execute();
        } catch (IOException e) {
            throw new ConnectionException("While submitting credentials", e);
        }
    }

    private Response getAsmToken(Map<String, String> cookies, String skypeToken) throws ConnectionException {
        try {
            return Jsoup.connect(TOKEN_AUTH_URL).cookies(cookies).data("skypetoken", skypeToken).method(Method.POST).execute();
        } catch (IOException e) {
            throw new ConnectionException("While fetching the asmtoken", e);
        }
    }

    private HttpURLConnection registerEndpoint(String skypeToken) throws ConnectionException {
        try {
            ConnectionBuilder builder = new ConnectionBuilder();
            builder.setUrl(ENDPOINTS_URL);
            builder.setMethod("POST", true);
            builder.addHeader("Authentication", String.format("skypetoken=%s", skypeToken));
            builder.setData("{}");

            HttpURLConnection connection = builder.build(); // LockAndKey data msmsgs@msnmsgr.com:Q1P7W2E4J9R8U3S5
            int code = connection.getResponseCode();
            if (code >= 301 && code <= 303 || code == 307) { //User is in a different cloud - let's go there
                builder.setUrl(connection.getHeaderField("Location"));
                updateCloud(connection.getHeaderField("Location"));
                connection = builder.build();
                code = connection.getResponseCode();
            }
            if (code == 201) {
                return connection;
            } else {
                throw generateException(connection);
            }
        } catch (IOException e) {
            throw new ConnectionException("While registering the endpoint", e);
        }
    }

    private JsonObject buildSubscriptionObject() {
        JsonObject subscriptionObject = new JsonObject();
        subscriptionObject.add("channelType", "httpLongPoll");
        subscriptionObject.add("template", "raw");
        JsonArray interestedResources = new JsonArray();
        interestedResources.add("/v1/users/ME/conversations/ALL/properties");
        interestedResources.add("/v1/users/ME/conversations/ALL/messages");
        interestedResources.add("/v1/users/ME/contacts/ALL");
        interestedResources.add("/v1/threads/ALL");
        subscriptionObject.add("interestedResources", interestedResources);
        return subscriptionObject;
    }

    private JsonObject buildRegistrationObject() {
        JsonObject registrationObject = new JsonObject();
        registrationObject.add("id", "messagingService");
        registrationObject.add("type", "EndpointPresenceDoc");
        registrationObject.add("selfLink", "uri");
        JsonObject publicInfo = new JsonObject();
        publicInfo.add("capabilities", "video|audio");
        publicInfo.add("type", 1);
        publicInfo.add("skypeNameVersion", "skype.com");
        publicInfo.add("nodeInfo", "xx");
        publicInfo.add("version", "908/1.6.0.288//skype.com");
        JsonObject privateInfo = new JsonObject();
        privateInfo.add("epname", "Skype4J");
        registrationObject.add("publicInfo", publicInfo);
        registrationObject.add("privateInfo", privateInfo);
        return registrationObject;
    }

    public void login() throws InvalidCredentialsException, ConnectionException, ParseException {
        final UUID guid = UUID.randomUUID();
        final Map<String, String> tCookies = new HashMap<>();
        final Response loginResponse = postToLogin(username, password);
        tCookies.putAll(loginResponse.cookies());
        Document loginResponseDocument;
        try {
            loginResponseDocument = loginResponse.parse();
        } catch (IOException e) {
            throw new ParseException("While parsing the login response", e);
        }
        Elements inputs = loginResponseDocument.select("input[name=skypetoken]");
        if (inputs.size() > 0) {
            String tSkypeToken = inputs.get(0).attr("value");

            Response asmResponse = getAsmToken(tCookies, tSkypeToken);
            tCookies.putAll(asmResponse.cookies());

            HttpURLConnection registrationToken = registerEndpoint(tSkypeToken);
            String[] splits = registrationToken.getHeaderField("Set-RegistrationToken").split(";");
            String tRegistrationToken = splits[0];
            String tEndpointId = splits[2].split("=")[1];

            this.skypeToken = tSkypeToken;
            this.registrationToken = tRegistrationToken;
            this.endpointId = tEndpointId;
            this.cookies = tCookies;

            sessionKeepaliveThread = new Thread(String.format("Skype-%s-Session", username)) {
                public void run() {
                    while (loggedIn.get()) {
                        try {
                            Jsoup.connect(PING_URL).header("X-Skypetoken", skypeToken).cookies(cookies).data("sessionId", guid.toString()).post();
                        } catch (IOException e) {
                            eventDispatcher.callEvent(new DisconnectedEvent(e));
                        }
                        try {
                            Thread.sleep(300000);
                        } catch (InterruptedException e) {
                            logger.log(Level.SEVERE, "Session thread was interrupted", e);
                        }
                    }
                }
            };
            sessionKeepaliveThread.start();
            this.eventDispatcher = new SkypeEventDispatcher();
            loggedIn.set(true);
        } else {
            Elements elements = loginResponseDocument.select(".message_error");
            if (elements.size() > 0) {
                Element div = elements.get(0);
                if (div.children().size() > 1) {
                    Element span = div.child(1);
                    throw new InvalidCredentialsException(span.text());
                }
            }
            throw new InvalidCredentialsException("Could not find error message. Dumping entire page. \n" + loginResponseDocument.html());
        }
    }

    public IOException generateException(HttpURLConnection connection) throws IOException {
        return new IOException(String.format("(%s, %s)", connection.getResponseCode(), connection.getResponseMessage()));
    }

    private void updateCloud(String anyLocation) {
        Pattern grabber = Pattern.compile("https?://([^-]*-)client-s");
        Matcher m = grabber.matcher(anyLocation);
        if (m.find()) {
            this.cloud = m.group(1);
        } else {
            throw new IllegalArgumentException("Could not find match in " + anyLocation);
        }
    }

    public String withCloud(String url, Object... extraArgs) {
        Object[] format = new Object[extraArgs.length + 1];
        format[0] = cloud;
        for (int i = 1; i < format.length; i++) {
            format[i] = extraArgs[i - 1].toString();
        }
        return String.format(url, format);
    }

    public String serializeCookies(Map<String, String> cookies) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> cookie : cookies.entrySet()) {
            result.append(cookie.getKey()).append("=").append(cookie.getValue()).append(";");
        }
        return result.toString();
    }

    @Override
    public String getUsername() {
        return this.username;
    }

}
