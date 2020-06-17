package org.jitsi.videobridge;

import org.jitsi.impl.neomedia.transform.TransformEngine;

public interface TransformChainManipulator {
    TransformEngine[] manipulate(TransformEngine[] paramArrayOfTransformEngine, Channel paramChannel);
}
