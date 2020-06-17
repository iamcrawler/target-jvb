package org.jitsi.videobridge.stats;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension;
import org.jitsi.xmpp.extensions.colibri.ColibriStatsIQ;

public abstract class Statistics {
    public static ColibriStatsExtension toXmppExtensionElement(Statistics statistics) {
        ColibriStatsExtension ext = new ColibriStatsExtension();
        for (Map.Entry<String, Object> e : statistics.getStats().entrySet())
            ext.addStat(new ColibriStatsExtension.Stat(e
                    .getKey(), e.getValue()));
        return ext;
    }

    public static ColibriStatsIQ toXmppIq(Statistics statistics) {
        ColibriStatsIQ iq = new ColibriStatsIQ();
        for (Map.Entry<String, Object> e : statistics.getStats().entrySet())
            iq.addStat(new ColibriStatsExtension.Stat(e
                    .getKey(), e.getValue()));
        return iq;
    }

    protected final ReadWriteLock lock = new ReentrantReadWriteLock();

    private final Map<String, Object> stats = new HashMap<>();

    public abstract void generate();

    public Object getStat(String stat) {
        Object value;
        Lock lock = this.lock.readLock();
        lock.lock();
        try {
            value = this.stats.get(stat);
        } finally {
            lock.unlock();
        }
        return value;
    }

    public double getStatAsDouble(String stat) {
        double d;
        Object o = getStat(stat);
        double defaultValue = 0.0D;
        if (o == null) {
            d = defaultValue;
        } else if (o instanceof Number) {
            d = ((Number)o).floatValue();
        } else {
            String s = o.toString();
            if (s == null || s.length() == 0) {
                d = defaultValue;
            } else {
                try {
                    d = Double.parseDouble(s);
                } catch (NumberFormatException nfe) {
                    d = defaultValue;
                }
            }
        }
        return d;
    }

    public float getStatAsFloat(String stat) {
        float f;
        Object o = getStat(stat);
        float defaultValue = 0.0F;
        if (o == null) {
            f = defaultValue;
        } else if (o instanceof Number) {
            f = ((Number)o).floatValue();
        } else {
            String s = o.toString();
            if (s == null || s.length() == 0) {
                f = defaultValue;
            } else {
                try {
                    f = Float.parseFloat(s);
                } catch (NumberFormatException nfe) {
                    f = defaultValue;
                }
            }
        }
        return f;
    }

    public int getStatAsInt(String stat) {
        int i;
        Object o = getStat(stat);
        int defaultValue = 0;
        if (o == null) {
            i = defaultValue;
        } else if (o instanceof Number) {
            i = ((Number)o).intValue();
        } else {
            String s = o.toString();
            if (s == null || s.length() == 0) {
                i = defaultValue;
            } else {
                try {
                    i = Integer.parseInt(s);
                } catch (NumberFormatException nfe) {
                    i = defaultValue;
                }
            }
        }
        return i;
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats;
        Lock lock = this.lock.readLock();
        lock.lock();
        try {
            stats = new HashMap<>(this.stats);
        } finally {
            lock.unlock();
        }
        return stats;
    }

    public void setStat(String stat, Object value) {
        Lock lock = this.lock.writeLock();
        lock.lock();
        try {
            unlockedSetStat(stat, value);
        } finally {
            lock.unlock();
        }
    }

    public String toString() {
        StringBuilder s = new StringBuilder();
        for (Map.Entry<String, Object> e : getStats().entrySet())
            s.append(e.getKey()).append(":").append(e.getValue()).append("\n");
        return s.toString();
    }

    protected void unlockedSetStat(String stat, Object value) {
        if (value == null) {
            this.stats.remove(stat);
        } else {
            this.stats.put(stat, value);
        }
    }
}
