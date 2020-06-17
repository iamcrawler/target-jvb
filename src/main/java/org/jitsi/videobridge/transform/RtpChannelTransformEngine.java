package org.jitsi.videobridge.transform;


import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import org.jitsi.impl.neomedia.MediaStreamImpl;
import org.jitsi.impl.neomedia.codec.video.vp8.DePacketizer;
import org.jitsi.impl.neomedia.transform.PacketTransformer;
import org.jitsi.impl.neomedia.transform.REDFilterTransformEngine;
import org.jitsi.impl.neomedia.transform.SinglePacketTransformerAdapter;
import org.jitsi.impl.neomedia.transform.TransformEngine;
import org.jitsi.impl.neomedia.transform.TransformEngineChain;
import org.jitsi.impl.neomedia.transform.delay.DelayingTransformEngine;
import org.jitsi.service.neomedia.RawPacket;
import org.jitsi.utils.logging.Logger;
import org.jitsi.videobridge.Channel;
import org.jitsi.videobridge.RtpChannel;
import org.jitsi.videobridge.TransformChainManipulator;
import org.jitsi.videobridge.VideoChannel;
import org.jitsi.videobridge.cc.BitrateController;

public class RtpChannelTransformEngine extends TransformEngineChain {
    private static final Logger classLogger = Logger.getLogger(RtpChannelTransformEngine.class);

    private static final byte RED_PAYLOAD_TYPE = 116;

    private final RtpChannel channel;

    private REDFilterTransformEngine redFilter;

    private final Logger logger;

    public RtpChannelTransformEngine(RtpChannel channel, Iterable<TransformChainManipulator> manipulators) {
        this.channel = channel;
        this
                .logger = Logger.getLogger(classLogger, channel

                .getContent().getConference().getLogger());
        TransformEngine[] chain = createChain();
        if (manipulators != null)
            for (TransformChainManipulator manipulator : manipulators)
                chain = manipulator.manipulate(chain, (Channel)channel);
        this.engineChain = chain;
    }

    class PacketDetailsObserver extends SinglePacketTransformerAdapter implements TransformEngine {
        public PacketTransformer getRTPTransformer() {
            return (PacketTransformer)this;
        }

        public PacketTransformer getRTCPTransformer() {
            return null;
        }

        public RawPacket reverseTransform(RawPacket pkt) {
            if (pkt == null || !RtpChannelTransformEngine.classLogger.isDebugEnabled() || !DePacketizer.isKeyFrame(pkt
                    .getBuffer(), pkt
                    .getPayloadOffset(), pkt
                    .getPayloadLength()))
                return pkt;
            RtpChannelTransformEngine.classLogger.debug((new StringBuilder())
                    .append("Observed an incoming keyframe ").append((
                            (MediaStreamImpl)RtpChannelTransformEngine.this.channel.getStream()).packetToString(pkt)));
            return pkt;
        }
    }

    private TransformEngine[] createChain() {
        List<TransformEngine> transformerList;
        boolean video = this.channel instanceof VideoChannel;
        if (video) {
            VideoChannel videoChannel = (VideoChannel)this.channel;
            transformerList = new LinkedList<>();
            BitrateController bitrateController = videoChannel.getBitrateController();
            if (bitrateController != null)
                transformerList.add(bitrateController);
            this.redFilter = new REDFilterTransformEngine((byte)116);
            transformerList.add(this.redFilter);
            transformerList.add(new PacketDetailsObserver());
        } else {
            transformerList = Collections.emptyList();
        }
        return transformerList
                .<TransformEngine>toArray(
                        new TransformEngine[transformerList.size()]);
    }

    public void enableREDFilter(boolean enabled) {
        if (this.redFilter != null)
            this.redFilter.setEnabled(enabled);
    }

    public boolean setPacketDelay(int packetDelay) {
        if (packetDelay > 0) {
            for (TransformEngine engine : this.engineChain) {
                if (engine instanceof DelayingTransformEngine) {
                    this.logger.warn("Can not modify packet-delay once it has been set.");
                    return false;
                }
            }
            if (addEngine((TransformEngine)new DelayingTransformEngine(packetDelay))) {
                this.logger.info("Adding delaying packet transformer to " + this.channel
                        .getID() + ", packet delay: " + packetDelay);
                return true;
            }
            this.logger.warn("Failed to add delaying packet transformer");
            return false;
        }
        return false;
    }
}