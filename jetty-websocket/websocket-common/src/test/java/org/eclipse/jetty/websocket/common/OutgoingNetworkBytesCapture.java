//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.common;

import static org.hamcrest.Matchers.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.websocket.api.WriteResult;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.junit.Assert;

/**
 * Capture outgoing network bytes.
 */
public class OutgoingNetworkBytesCapture implements OutgoingFrames
{
    private final Generator generator;
    private List<ByteBuffer> captured;

    public OutgoingNetworkBytesCapture(Generator generator)
    {
        this.generator = generator;
        this.captured = new ArrayList<>();
    }

    public void assertBytes(int idx, String expectedHex)
    {
        Assert.assertThat("Capture index does not exist",idx,lessThan(captured.size()));
        ByteBuffer buf = captured.get(idx);
        String actualHex = TypeUtil.toHexString(BufferUtil.toArray(buf)).toUpperCase(Locale.ENGLISH);
        Assert.assertThat("captured[" + idx + "]",actualHex,is(expectedHex.toUpperCase(Locale.ENGLISH)));
    }

    public List<ByteBuffer> getCaptured()
    {
        return captured;
    }

    @Override
    public Future<WriteResult> outgoingFrame(Frame frame) throws IOException
    {
        ByteBuffer buf = generator.generate(frame);
        captured.add(buf.slice());

        return FinishedFuture.INSTANCE;
    }
}
