

package org.wisdom.engine.wrapper.cookies;

import org.wisdom.api.configuration.ApplicationConfiguration;
import org.wisdom.api.cookies.Cookie;
import org.wisdom.api.cookies.SessionCookie;
import org.wisdom.api.crypto.Crypto;
import org.wisdom.api.http.Context;
import org.wisdom.api.http.Result;
import org.wisdom.api.utils.CookieDataCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Session Cookie... Mostly an adaption of Play1's excellent cookie system that
 * in turn is based on the new client side rails cookies.
 */
public class SessionCookieImpl implements SessionCookie {

    public static final String SESSION_SUFFIX = "_SESSION";
    private static final String AUTHENTICITY_KEY = "___AT";
    private static final String ID_KEY = "___ID";
    private static final String TIMESTAMP_KEY = "___TS";

    private static Logger logger = LoggerFactory.getLogger(SessionCookieImpl.class);
    private final Integer sessionExpireTimeInMs;
    private final Boolean sessionSendOnlyIfChanged;
    private final Boolean sessionTransferredOverHttpsOnly;
    private final Boolean sessionHttpOnly;
    private final String applicationCookiePrefix;
    private final Map<String, String> data = new HashMap<>();
    /**
     * The crypto service.
     */
    private final Crypto crypto;
    /**
     * Has cookie been changed => only send new cookie stuff has been changed
     */
    private boolean sessionDataHasBeenChanged = false;

    public SessionCookieImpl(Crypto crypto, ApplicationConfiguration configuration) {
        applicationCookiePrefix = configuration.getWithDefault(Cookie.applicationCookiePrefix, "wisdom");
        this.crypto = crypto;

        // read configuration stuff:
        sessionExpireTimeInMs =
                configuration.getIntegerWithDefault(SessionCookie.sessionExpireTimeInSeconds, 3600) * 1000;


        this.sessionSendOnlyIfChanged = configuration.getBooleanWithDefault(
                SessionCookie.sessionSendOnlyIfChanged, true);
        this.sessionTransferredOverHttpsOnly = configuration
                .getBooleanWithDefault(
                        SessionCookie.sessionTransferredOverHttpsOnly, true);
        this.sessionHttpOnly = configuration.getBooleanWithDefault(
                SessionCookie.sessionHttpOnly, true);
    }

    /**
     * Has to be called initially.
     *
     * @param context the current http context.
     */
    @Override
    public void init(Context context) {

        try {

            // get the cookie that contains session information:
            Cookie cookie = context.request().cookie(applicationCookiePrefix
                    + SESSION_SUFFIX);

            // check that the cookie is not empty:
            if (cookie != null && cookie.value() != null
                    && !cookie.value().trim().equals("")
                    && cookie.value().contains("-")) {

                String value = cookie.value();
                // the first substring until "-" is the sign
                String sign = value.substring(0, value.indexOf("-"));

                // rest from "-" until the end it the payload of the cookie
                String payload = value.substring(value.indexOf("-") + 1);

                if (CookieDataCodec.safeEquals(sign,
                        crypto.sign(payload))) {
                    CookieDataCodec.decode(data, payload);
                } else {
                    LoggerFactory.getLogger(SessionCookieImpl.class).warn("Invalid session cookie");
                }

                // Make sure session contains valid timestamp
                if (!data.containsKey(TIMESTAMP_KEY)) {
                    data.clear();
                } else {
                    if (Long.parseLong(data.get(TIMESTAMP_KEY))
                            + sessionExpireTimeInMs < System
                            .currentTimeMillis()) {
                        // Session expired
                        sessionDataHasBeenChanged = true;
                        data.clear();
                    }
                }

                // Everything's alright => prolong session
                data.put(TIMESTAMP_KEY, "" + System.currentTimeMillis());
            }

        } catch (UnsupportedEncodingException unsupportedEncodingException) {
            logger.error("Encoding exception - this must not happen", unsupportedEncodingException);
        }
    }

    /**
     * @return id of a session.
     */
    @Override
    public String getId() {
        if (!data.containsKey(ID_KEY)) {
            data.put(ID_KEY, UUID.randomUUID().toString());
        }
        return data.get(ID_KEY);

    }

    /**
     * @return complete content of session.
     */
    @Override
    public Map<String, String> getData() {
        return data;
    }

    /**
     * @return an authenticity token or generates a new one.
     */
    @Override
    public String getAuthenticityToken() {
        if (!data.containsKey(AUTHENTICITY_KEY)) {
            data.put(AUTHENTICITY_KEY, UUID.randomUUID().toString());
        }
        return data.get(AUTHENTICITY_KEY);
    }

    @Override
    public void save(Context context, Result result) {

        // Don't save the cookie nothing has changed, and if we're not expiring
        // or
        // we are expiring but we're only updating if the session changes
        if (!sessionDataHasBeenChanged && (sessionSendOnlyIfChanged)) {
            // Nothing changed and no cookie-expire, consequently send nothing
            // back.
            return;
        }

        if (isEmpty()) {
            // It is empty, but there was a session coming in, therefore clear
            // it
            if (context.hasCookie(applicationCookiePrefix
                    + SESSION_SUFFIX)) {

                Cookie.Builder expiredSessionCookie = Cookie.builder(
                        applicationCookiePrefix + SESSION_SUFFIX,
                        "");
                expiredSessionCookie.setPath("/");
                expiredSessionCookie.setMaxAge(0);

                result.addCookie(expiredSessionCookie.build());

            }
            return;

        }

        // Make sure if has a timestamp, if it needs one
        if (!data.containsKey(TIMESTAMP_KEY)) {
            data.put(TIMESTAMP_KEY, Long.toString(System.currentTimeMillis()));
        }

        try {
            String sessionData = CookieDataCodec.encode(data);

            String sign = crypto.sign(sessionData);

            Cookie.Builder cookie = Cookie.builder(applicationCookiePrefix
                    + SESSION_SUFFIX, sign + "-" + sessionData);
            cookie.setPath("/");

            cookie.setMaxAge(sessionExpireTimeInMs / 1000);
            if (sessionTransferredOverHttpsOnly != null) {
                cookie.setSecure(sessionTransferredOverHttpsOnly);
            }
            if (sessionHttpOnly != null) {
                cookie.setHttpOnly(sessionHttpOnly);
            }

            result.addCookie(cookie.build());

        } catch (UnsupportedEncodingException unsupportedEncodingException) {
            logger.error("Encoding exception - this must not happen", unsupportedEncodingException);
        }

    }

    /**
     * Puts key into session. PLEASE NOTICE: If value == null the key will be
     * removed!
     *
     * @param key   the key
     * @param value the value
     */
    @Override
    public void put(String key, String value) {

        // make sure key is valid:
        if (key.contains(":")) {
            throw new IllegalArgumentException(
                    "Character ':' is invalid in a session key.");
        }

        sessionDataHasBeenChanged = true;

        if (value == null) {
            remove(key);
        } else {
            data.put(key, value);
        }

    }

    /**
     * Returns the value of the key or null.
     *
     * @param key the key
     * @return the value
     */
    @Override
    public String get(String key) {
        return data.get(key);
    }

    @Override
    public String remove(String key) {

        sessionDataHasBeenChanged = true;
        String result = get(key);
        data.remove(key);
        return result;
    }

    @Override
    public void clear() {
        sessionDataHasBeenChanged = true;
        data.clear();
    }

    /**
     * Returns true if the session is empty, e.g. does not contain anything else
     * than the timestamp key.
     */
    @Override
    public boolean isEmpty() {
        return (data.isEmpty() || data.size() == 1
                && data.containsKey(TIMESTAMP_KEY));
    }

}
