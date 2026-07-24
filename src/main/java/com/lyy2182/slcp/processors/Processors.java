package com.lyy2182.slcp.processors;

import com.lyy2182.slcp.SLCPConfig;

import java.io.InputStream;

public interface Processors {
    SLCPConfig.Entry preProcessor(SLCPConfig.Entry data);

    InputStream postProcessor(SLCPConfig.Entry entry, InputStream data);
}
