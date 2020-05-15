package com.beeplay.easylog.demo.dubbo.service;

import com.beeplay.easylog.trace.annotation.Trace;
import org.apache.dubbo.config.annotation.Service;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * @ClassName EasyLogDubboService
 * @Deacription TODO
 * @Author Frank.Chen
 * @Date 2020/5/13 16:02
 * @Version 1.0
 **/
@Service
@Component
public class EasyLogDubboServiceImpl implements EasyLogDubboService{

    private static org.slf4j.Logger logger = LoggerFactory.getLogger(EasyLogDubboServiceImpl.class);

    @Trace
    @Override
    public void testLogDubbo(){
        logger.info("I am EasyLogDubboService testLogDubbo");
    }

}