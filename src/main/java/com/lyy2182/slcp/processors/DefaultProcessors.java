package com.lyy2182.slcp.processors;

import com.lyy2182.slcp.SLCPConfig;

import java.io.InputStream;

public class DefaultProcessors implements Processors{
    @Override
    public SLCPConfig.Entry preProcessor(SLCPConfig.Entry data) {
        return data;
    }

    @Override
    public InputStream postProcessor(SLCPConfig.Entry entry, InputStream data) {
        return data;
    }

}
