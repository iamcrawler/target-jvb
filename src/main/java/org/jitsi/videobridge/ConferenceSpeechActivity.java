package org.jitsi.videobridge;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import org.jitsi.utils.MediaType;
import org.jitsi.utils.concurrent.ExecutorUtils;
import org.jitsi.utils.dsi.ActiveSpeakerChangedListener;
import org.jitsi.utils.dsi.ActiveSpeakerDetector;
import org.jitsi.utils.dsi.DominantSpeakerIdentification;
import org.jitsi.utils.event.PropertyChangeNotifier;
import org.jitsi.utils.event.WeakReferencePropertyChangeListener;
import org.jitsi.utils.logging.Logger;
import org.json.simple.JSONObject;

public class ConferenceSpeechActivity extends PropertyChangeNotifier implements PropertyChangeListener {
    public static final String DOMINANT_ENDPOINT_PROPERTY_NAME = ConferenceSpeechActivity.class
            .getName() + ".dominantEndpoint";

    public static final String ENDPOINTS_PROPERTY_NAME = ConferenceSpeechActivity.class
            .getName() + ".endpoints";

    private static final ExecutorService executorService = ExecutorUtils.newCachedThreadPool(true, "ConferenceSpeechActivity");

    private static final Logger logger = Logger.getLogger(ConferenceSpeechActivity.class);

    private static long parseSSRC(Object obj) {
        long l;
        if (obj == null) {
            l = -1L;
        } else if (obj instanceof Number) {
            l = ((Number)obj).longValue();
        } else {
            String s = obj.toString();
            if (s == null) {
                l = -1L;
            } else {
                try {
                    l = Long.parseLong(s);
                } catch (NumberFormatException ex) {
                    l = -1L;
                }
            }
        }
        return l;
    }

    private static void resolveSSRCAsEndpoint(JSONObject jsonObject, String ssrcKey, Conference conference, String endpointKey) {
        long ssrc = parseSSRC(jsonObject.get(ssrcKey));
        if (ssrc != -1L) {
            AbstractEndpoint endpoint = conference.findEndpointByReceiveSSRC(ssrc, MediaType.AUDIO);
            if (endpoint != null)
                jsonObject.put(endpointKey, endpoint.getID());
        }
    }

    private final ActiveSpeakerChangedListener activeSpeakerChangedListener = this::activeSpeakerChanged;

    private final Object activeSpeakerDetectorSyncRoot = new Object();

    private Conference conference;

    private AbstractEndpoint dominantEndpoint;

    private boolean dominantEndpointChanged = false;

    private DominantSpeakerIdentification dominantSpeakerIdentification;

    private List<AbstractEndpoint> endpoints;

    private boolean endpointsChanged = false;

    private EventDispatcher eventDispatcher;

    private long eventDispatcherTime;

    private final PropertyChangeListener propertyChangeListener = (PropertyChangeListener)new WeakReferencePropertyChangeListener(this);

    private final Object syncRoot = new Object();

    public ConferenceSpeechActivity(Conference conference) {
        this.conference = Objects.<Conference>requireNonNull(conference, "conference");
        conference.addPropertyChangeListener(this.propertyChangeListener);
    }

    private void activeSpeakerChanged(long ssrc) {
        Conference conference = getConference();
        if (conference != null) {
            if (logger.isTraceEnabled())
                logger.trace("The dominant speaker in conference " + conference

                        .getID() + " is now the SSRC " + ssrc + ".");
            AbstractEndpoint endpoint = conference.findEndpointByReceiveSSRC(ssrc, MediaType.AUDIO);
            boolean maybeStartEventDispatcher = false;
            synchronized (this.syncRoot) {
                if (endpoint == null) {
                    maybeStartEventDispatcher = true;
                } else {
                    AbstractEndpoint dominantEndpoint = getDominantEndpoint();
                    if (!endpoint.equals(dominantEndpoint)) {
                        this.dominantEndpoint = endpoint;
                        maybeStartEventDispatcher = true;
                    }
                }
                if (maybeStartEventDispatcher) {
                    this.dominantEndpointChanged = true;
                    maybeStartEventDispatcher();
                }
            }
        }
    }

    public JSONObject doGetDominantSpeakerIdentificationJSON() {
        JSONObject jsonObject;
        DominantSpeakerIdentification dominantSpeakerIdentification = getDominantSpeakerIdentification();
        if (dominantSpeakerIdentification == null) {
            jsonObject = null;
        } else {
            Conference conference = getConference();
            if (conference == null) {
                jsonObject = null;
            } else {
                jsonObject = dominantSpeakerIdentification.doGetJSON();
                if (jsonObject != null) {
                    resolveSSRCAsEndpoint(jsonObject, "dominantSpeaker", conference, "dominantEndpoint");
                    Object speakers = jsonObject.get("speakers");
                    if (speakers != null)
                        if (speakers instanceof JSONObject[]) {
                            for (JSONObject speaker : (JSONObject[])speakers)
                                resolveSSRCAsEndpoint(speaker, "ssrc", conference, "endpoint");
                        } else if (speakers instanceof org.json.simple.JSONArray) {
                            for (Object speaker : Arrays.asList(speakers)) {
                                if (speaker instanceof JSONObject)
                                    resolveSSRCAsEndpoint((JSONObject)speaker, "ssrc", conference, "endpoint");
                            }
                        }
                }
            }
        }
        return jsonObject;
    }

    private void eventDispatcherExited(EventDispatcher eventDispatcher) {
        synchronized (this.syncRoot) {
            if (this.eventDispatcher == eventDispatcher) {
                this.eventDispatcher = eventDispatcher;
                this.eventDispatcherTime = 0L;
            }
        }
    }

    private ActiveSpeakerDetector getActiveSpeakerDetector() {
        DominantSpeakerIdentification dominantSpeakerIdentification;
        boolean addActiveSpeakerChangedListener = false;
        synchronized (this.activeSpeakerDetectorSyncRoot) {
            dominantSpeakerIdentification = this.dominantSpeakerIdentification;
            if (dominantSpeakerIdentification == null) {
                DominantSpeakerIdentification dsi = new DominantSpeakerIdentification();
                addActiveSpeakerChangedListener = true;
                this.dominantSpeakerIdentification = dominantSpeakerIdentification = dsi;
            }
        }
        if (addActiveSpeakerChangedListener) {
            Conference conference = getConference();
            if (conference != null) {
                dominantSpeakerIdentification.addActiveSpeakerChangedListener(this.activeSpeakerChangedListener);
                dominantSpeakerIdentification.addPropertyChangeListener(this.propertyChangeListener);
            }
        }
        return (ActiveSpeakerDetector)dominantSpeakerIdentification;
    }

    private Conference getConference() {
        Conference conference = this.conference;
        if (conference != null && conference.isExpired()) {
            this.conference = conference = null;
            DominantSpeakerIdentification dominantSpeakerIdentification = this.dominantSpeakerIdentification;
            if (dominantSpeakerIdentification != null) {
                dominantSpeakerIdentification.removeActiveSpeakerChangedListener(this.activeSpeakerChangedListener);
                dominantSpeakerIdentification.removePropertyChangeListener(this.propertyChangeListener);
            }
        }
        return conference;
    }

    public AbstractEndpoint getDominantEndpoint() {
        AbstractEndpoint dominantEndpoint;
        synchronized (this.syncRoot) {
            if (this.dominantEndpoint == null) {
                dominantEndpoint = null;
            } else {
                dominantEndpoint = this.dominantEndpoint;
                if (dominantEndpoint.isExpired())
                    this.dominantEndpoint = null;
            }
        }
        return dominantEndpoint;
    }

    private DominantSpeakerIdentification getDominantSpeakerIdentification() {
        getActiveSpeakerDetector();
        return this.dominantSpeakerIdentification;
    }

    public List<AbstractEndpoint> getEndpoints() {
        List<AbstractEndpoint> ret;
        synchronized (this.syncRoot) {
            if (this.endpoints == null) {
                Conference conference = getConference();
                if (conference == null) {
                    this.endpoints = new ArrayList<>();
                } else {
                    List<AbstractEndpoint> conferenceEndpoints = conference.getEndpoints();
                    this.endpoints = new ArrayList<>(conferenceEndpoints);
                }
            }
            ret = new ArrayList<>(this.endpoints);
            ret.removeIf(Objects::isNull);
        }
        return ret;
    }

    public void levelChanged(Channel channel, long ssrc, int level) {
        ActiveSpeakerDetector activeSpeakerDetector = getActiveSpeakerDetector();
        if (activeSpeakerDetector != null)
            activeSpeakerDetector.levelChanged(ssrc, level);
    }

    private void maybeStartEventDispatcher() {
        synchronized (this.syncRoot) {
            if (this.eventDispatcher == null) {
                EventDispatcher eventDispatcher = new EventDispatcher(this);
                boolean scheduled = false;
                this.eventDispatcher = eventDispatcher;
                this.eventDispatcherTime = 0L;
                try {
                    executorService.execute(eventDispatcher);
                    scheduled = true;
                } finally {
                    if (!scheduled && this.eventDispatcher == eventDispatcher) {
                        this.eventDispatcher = null;
                        this.eventDispatcherTime = 0L;
                    }
                }
            } else {
                this.syncRoot.notify();
            }
        }
    }

    public void propertyChange(PropertyChangeEvent ev) {
        Conference conference = getConference();
        if (conference == null)
            return;
        String propertyName = ev.getPropertyName();
        if (Conference.ENDPOINTS_PROPERTY_NAME.equals(propertyName))
            if (conference.equals(ev.getSource()))
                synchronized (this.syncRoot) {
                    this.endpointsChanged = true;
                    maybeStartEventDispatcher();
                }
    }

    private boolean runInEventDispatcher(EventDispatcher eventDispatcher) {
        boolean endpointsChanged = false;
        boolean dominantEndpointChanged = false;
        synchronized (this.syncRoot) {
            if (this.eventDispatcher != eventDispatcher)
                return false;
            Conference conference = getConference();
            if (conference == null)
                return false;
            long now = System.currentTimeMillis();
            if (!this.dominantEndpointChanged && !this.endpointsChanged) {
                long wait = 100L - now - this.eventDispatcherTime;
                if (wait > 0L) {
                    try {
                        this.syncRoot.wait(wait);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    return true;
                }
            }
            this.eventDispatcherTime = now;
            List<AbstractEndpoint> conferenceEndpoints = conference.getEndpoints();
            if (this.endpoints == null) {
                this.endpoints = new ArrayList<>(conferenceEndpoints);
                endpointsChanged = true;
            } else {
                endpointsChanged = this.endpoints.removeIf(e ->
                        (e.isExpired() || !conferenceEndpoints.contains(e)));
                conferenceEndpoints.removeAll(this.endpoints);
                endpointsChanged |= this.endpoints.addAll(conferenceEndpoints);
            }
            this.endpointsChanged = false;
            AbstractEndpoint dominantEndpoint = getDominantEndpoint();
            if (dominantEndpoint != null) {
                this.endpoints.remove(dominantEndpoint);
                this.endpoints.add(0, dominantEndpoint);
            }
            if (this.dominantEndpointChanged) {
                dominantEndpointChanged = true;
                this.dominantEndpointChanged = false;
            }
        }
        if (endpointsChanged)
            firePropertyChange(ENDPOINTS_PROPERTY_NAME, null, null);
        if (dominantEndpointChanged)
            firePropertyChange(DOMINANT_ENDPOINT_PROPERTY_NAME, null, null);
        return true;
    }

    private static class EventDispatcher implements Runnable {
        private final WeakReference<ConferenceSpeechActivity> owner;

        public EventDispatcher(ConferenceSpeechActivity owner) {
            this.owner = new WeakReference<>(owner);
        }

        public void run() {
            try {
                ConferenceSpeechActivity owner;
                do {
                    owner = this.owner.get();
                } while (owner != null && owner.runInEventDispatcher(this));
            } finally {
                ConferenceSpeechActivity owner = this.owner.get();
                if (owner != null)
                    owner.eventDispatcherExited(this);
            }
        }
    }
}
