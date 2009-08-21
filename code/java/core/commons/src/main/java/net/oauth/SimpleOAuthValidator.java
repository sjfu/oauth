/*
 * Copyright 2008 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.oauth;

import java.util.List;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.oauth.signature.OAuthSignatureMethod;

/**
 * A simple OAuthValidator, which checks the version, whether the timestamp is
 * close to now, the nonce hasn't been used before and the signature is valid.
 * Each check may be overridden.
 * <p>
 * Calling releaseGarbage periodically is recommended, to free up space used to
 * remember old requests.
 * 
 * @author Dirk Balfanz
 * @author John Kristian
 */
public class SimpleOAuthValidator implements OAuthValidator {

    /** The default window for timestamps is 5 minutes. */
    public static final long DEFAULT_TIMESTAMP_WINDOW = 5 * 60 * 1000L;

    /**
     * Names of parameters that may not appear twice in a valid message.
     * This limitation is specified by OAuth Core <a
     * href="http://oauth.net/core/1.0#anchor7">section 5</a>.
     */
    public static final Set<String> SINGLE_PARAMETERS = constructSingleParameters();

    private static Set<String> constructSingleParameters() {
        Set<String> s = new HashSet<String>();
        for (String p : new String[] { OAuth.OAUTH_CONSUMER_KEY, OAuth.OAUTH_TOKEN, OAuth.OAUTH_TOKEN_SECRET,
                OAuth.OAUTH_CALLBACK, OAuth.OAUTH_SIGNATURE_METHOD, OAuth.OAUTH_SIGNATURE, OAuth.OAUTH_TIMESTAMP,
                OAuth.OAUTH_NONCE, OAuth.OAUTH_VERSION }) {
            s.add(p);
        }
        return Collections.unmodifiableSet(s);
    }

    /**
     * Construct a validator that rejects messages more than five minutes out
     * of date, or with a OAuth version other than 1.0, or with an invalid
     * signature.
     */
    public SimpleOAuthValidator() {
        this(DEFAULT_TIMESTAMP_WINDOW, Double.parseDouble(OAuth.VERSION_1_0));
    }

    /**
     * Public constructor.
     *
     * @param timestampWindowSec
     *            specifies, in seconds, the windows (into the past and
     *            into the future) in which we'll accept timestamps.
     * @param maxVersion
     *            the maximum acceptable oauth_version
     */
    public SimpleOAuthValidator(long timestampWindowMsec, double maxVersion) {
        this.timestampWindow = timestampWindowMsec;
        this.maxVersion = maxVersion;
    }

    protected final double minVersion = 1.0;
    protected final double maxVersion;
    protected final long timestampWindow;
    protected final Set<TimestampAndNonce> usedNonces = new TreeSet<TimestampAndNonce>();

    /** Allow objects that are no longer useful to become garbage. */
    public void releaseGarbage() {
        releaseUsedNonces(currentTimeMsec() - timestampWindow);
    }

    /** Removed usedNonces older than the given time. */
    private void releaseUsedNonces(long minimumTime) {
        TimestampAndNonce limit = new TimestampAndNonce(minimumTime);
        synchronized (usedNonces) {
            // Because usedNonces is a TreeSet, the iterator produces
            // elements in their natural order, from oldest to newest.
            for (Iterator<TimestampAndNonce> iter = usedNonces.iterator(); iter.hasNext();) {
                TimestampAndNonce t = iter.next();
                if (limit.compareTo(t) <= 0)
                    break;
                iter.remove(); // too old
            }
        }
    }

    /** {@inherit} 
     * @throws URISyntaxException */
    public void validateMessage(OAuthMessage message, OAuthAccessor accessor)
    throws OAuthException, IOException, URISyntaxException {
        checkSingleParameters(message);
        validateVersion(message);
        validateTimestampAndNonce(message);
        validateSignature(message, accessor);
    }

    /** Throw an exception if any SINGLE_PARAMETERS occur repeatedly. */
    protected void checkSingleParameters(OAuthMessage message) throws IOException, OAuthException {
        // Check for repeated oauth_ parameters:
        boolean repeated = false;
        Map<String, Collection<String>> nameToValues = new HashMap<String, Collection<String>>();
        for (Map.Entry<String, String> parameter : message.getParameters()) {
            String name = parameter.getKey();
            if (SINGLE_PARAMETERS.contains(name)) {
                Collection<String> values = nameToValues.get(name);
                if (values == null) {
                    values = new ArrayList<String>();
                    nameToValues.put(name, values);
                } else {
                    repeated = true;
                }
                values.add(parameter.getValue());
            }
        }
        if (repeated) {
            Collection<OAuth.Parameter> rejected = new ArrayList<OAuth.Parameter>();
            for (Map.Entry<String, Collection<String>> p : nameToValues.entrySet()) {
                String name = p.getKey();
                Collection<String> values = p.getValue();
                if (values.size() > 1) {
                    for (String value : values) {
                        rejected.add(new OAuth.Parameter(name, value));
                    }
                }
            }
            OAuthProblemException problem = new OAuthProblemException(OAuth.Problems.PARAMETER_REJECTED);
            problem.setParameter(OAuth.Problems.OAUTH_PARAMETERS_REJECTED, OAuth.formEncode(rejected));
            throw problem;
        }
    }

    protected void validateVersion(OAuthMessage message)
    throws OAuthException, IOException {
        String versionString = message.getParameter(OAuth.OAUTH_VERSION);
        if (versionString != null) {
            double version = Double.parseDouble(versionString);
            if (version < minVersion || maxVersion < version) {
                OAuthProblemException problem = new OAuthProblemException(OAuth.Problems.VERSION_REJECTED);
                problem.setParameter(OAuth.Problems.OAUTH_ACCEPTABLE_VERSIONS, minVersion + "-" + maxVersion);
                throw problem;
            }
        }
    }

    /**
     * Throw an exception if the timestamp is out of range or the nonce has been
     * validated previously.
     */
    protected void validateTimestampAndNonce(OAuthMessage message)
    throws IOException, OAuthProblemException {
        message.requireParameters(OAuth.OAUTH_TIMESTAMP, OAuth.OAUTH_NONCE);
        long timestamp = Long.parseLong(message.getParameter(OAuth.OAUTH_TIMESTAMP)) * 1000L;
        long now = currentTimeMsec();
        long min = now - timestampWindow;
        long max = now + timestampWindow;
        if (timestamp < min || max < timestamp) {
            OAuthProblemException problem = new OAuthProblemException(OAuth.Problems.TIMESTAMP_REFUSED);
            problem.setParameter(OAuth.Problems.OAUTH_ACCEPTABLE_TIMESTAMPS, min + "-" + max);
            throw problem;
        }
        TimestampAndNonce nonce = new TimestampAndNonce(timestamp,
                message.getParameter(OAuth.OAUTH_NONCE), message.getConsumerKey(), message.getToken());
        // One might argue that the token should be omitted from the stored nonce.
        // But I imagine a Consumer might be unable to coordinate the coining of
        // nonces by clients on many computers, each with its own token.
        synchronized (usedNonces) {
            if (!usedNonces.add(nonce)) {
                // It was already in the set.
                throw new OAuthProblemException(OAuth.Problems.NONCE_USED);
            }
        }
        releaseUsedNonces(min);
    }

    protected void validateSignature(OAuthMessage message, OAuthAccessor accessor)
    throws OAuthException, IOException, URISyntaxException {
        message.requireParameters(OAuth.OAUTH_CONSUMER_KEY,
                OAuth.OAUTH_SIGNATURE_METHOD, OAuth.OAUTH_SIGNATURE);
        OAuthSignatureMethod.newSigner(message, accessor).validate(message);
    }

    protected long currentTimeMsec() {
        return System.currentTimeMillis();
    }

    /**
     * Selected parameters from an OAuth request, in a form suitable for
     * detecting duplicate requests. The timestamp, nonce, consumer key and
     * request or access token are contained. Two objects are equal only if all
     * of their components are equal. The timestamp is most significant for
     * comparison.
     * 
     * @author John Kristian
     */
    private static class TimestampAndNonce implements Comparable<TimestampAndNonce> {
        /*
         * The implementation is optimized for the comparison operations
         * (compareTo, equals and hashCode).
         */
        private final String sortKey;

        TimestampAndNonce(long timestamp, String nonce, String consumerKey, String token) {
            List<String> etc = new ArrayList<String>(3);
            etc.add(nonce);
            etc.add(consumerKey);
            etc.add(token);
            sortKey = String.format("%20d&%s", Long.valueOf(timestamp), OAuth.percentEncode(etc));
        }

        /**
         * Construct an object that's greater than other objects whose timestamp
         * is less than the given timestamp, and less than or equal to all other
         * objects. (It's less than objects with the same timestamp and a nonce
         * etc.) This is useful for identifying stale objects, by comparing them
         * to an object with the oldest acceptable timestamp.
         */
        TimestampAndNonce(long timestamp) {
            sortKey = String.format("%20d", Long.valueOf(timestamp));
        }

        public int compareTo(TimestampAndNonce that) {
            return (that == null) ? 1 : sortKey.compareTo(that.sortKey);
        }

        @Override
        public int hashCode() {
            return sortKey.hashCode();
        }

        @Override
        public boolean equals(Object that) {
            if (that == null)
                return false;
            if (that == this)
                return true;
            if (that.getClass() != getClass())
                return false;
            return sortKey.equals(((TimestampAndNonce) that).sortKey);
        }

        @Override
        public String toString() {
            return sortKey;
        }
    }
}
