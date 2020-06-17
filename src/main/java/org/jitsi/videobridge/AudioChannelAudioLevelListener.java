package org.jitsi.videobridge;


import org.jitsi.service.neomedia.event.CsrcAudioLevelListener;

public class AudioChannelAudioLevelListener implements CsrcAudioLevelListener {
    private final RtpChannel channel;

    public AudioChannelAudioLevelListener(RtpChannel channel) {
        this.channel = channel;
    }

    public void audioLevelsReceived(long[] levels) {
        if (levels != null) {
            int[] receiveSSRCs = this.channel.getReceiveSSRCs();
            if (receiveSSRCs.length != 0)
                for (int i = 0, count = levels.length / 2; i < count; i++) {
                    int i2 = i * 2;
                    long ssrc = levels[i2];
                    boolean isReceiveSSRC = false;
                    for (int receiveSSRC : receiveSSRCs) {
                        if (ssrc == (0xFFFFFFFFL & receiveSSRC)) {
                            isReceiveSSRC = true;
                            break;
                        }
                    }
                    if (isReceiveSSRC) {
                        ConferenceSpeechActivity conferenceSpeechActivity = this.channel.conferenceSpeechActivity;
                        if (conferenceSpeechActivity != null) {
                            int level = (int)levels[i2 + 1];
                            conferenceSpeechActivity.levelChanged(this.channel, ssrc, level);
                        }
                    }
                }
        }
    }
}

