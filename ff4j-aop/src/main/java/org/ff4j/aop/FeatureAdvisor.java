package org.ff4j.aop;

/*
 * #%L ff4j-aop %% Copyright (C) 2013 Ff4J %% Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License. #L%
 */

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.lang.model.type.NullType;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.ff4j.FF4j;
import org.ff4j.core.FlippingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * When Proxified, analyze bean to eventually invoke ANOTHER implementation (flip up).
 * 
 * @author <a href="mailto:cedrick.lunven@gmail.com">Cedrick LUNVEN</a>
 */
@Component("ff.advisor")
public class FeatureAdvisor implements MethodInterceptor, BeanPostProcessor, ApplicationContextAware {

    /** Log with target className. */
    private final Map<String, Logger> targetLoggers = new HashMap<String, Logger>();

    /** Processed Interfaces. */
    private final Set<String> targetInterfacesNames = new HashSet<String>();

    /** Strategies should be instanciate only once, keep references */
    private final Map<String, FlippingStrategy> strategySingletons = new HashMap<String, FlippingStrategy>();

    /** Spring Application Context. */
    private ApplicationContext appCtx;

    /** Injection of current FF4J bean. */
    @Autowired
    private FF4j ff4j;

    /** {@inheritDoc} */
    @Override
    public Object invoke(final MethodInvocation pMInvoc) throws Throwable {
        Method method = pMInvoc.getMethod();
        Logger targetLogger = getLogger(method);
        Flip ff = null;
        if (method.isAnnotationPresent(Flip.class)) {
            ff = method.getAnnotation(Flip.class);
        } else if (method.getDeclaringClass().isAnnotationPresent(Flip.class)) {
            ff = method.getDeclaringClass().getAnnotation(Flip.class);
        }
        
        if (ff != null&& shouldFlip(ff)) {
           
            // Required parameters
            if (!assertRequiredParams(ff)) {
                String msg = String.format("alterBeanName or alterClazz is required for {}", method.getDeclaringClass());
                throw new IllegalArgumentException(msg);
            }
            if (shouldCallAlterBeanMethod(pMInvoc, ff.alterBean(), targetLogger)) {
                return callAlterBeanMethod(pMInvoc, ff.alterBean(), targetLogger);
            }
            // Test alterClazz Property of annotation
            if (shouldCallAlterClazzMethod(pMInvoc, ff.alterClazz(), targetLogger)) {
                return callAlterClazzMethodOnFirst(pMInvoc, ff, targetLogger);
            }
        }
        // do not catch throwable
        return pMInvoc.proceed();
    }

    /**
     * Check requirements for annoting methods.
     * 
     * @param ff
     *            target annotation {@link Flip}
     * @return is param are correct
     */
    private boolean assertRequiredParams(Flip ff) {
        boolean alterBeanPresent = (ff.alterBean() != null && !ff.alterBean().isEmpty());
        boolean alterClazPresent = ((ff.alterClazz() != null) && (ff.alterClazz() != NullType.class));
        return alterBeanPresent || alterClazPresent;
    }

    /** {@inheritDoc} */
    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        // Before Initializing allow to check Annotations
        Class<?> target = bean.getClass();
        // Scan interface only once.
        if (!target.isInterface() && target.getInterfaces() != null) {
            // Get Interface
            for (Class<?> currentInterface : target.getInterfaces()) {
                String currentInterfaceName = currentInterface.getCanonicalName();
                if (!currentInterfaceName.startsWith("java.") && !targetInterfacesNames.contains(currentInterfaceName)) {
                    targetInterfacesNames.add(currentInterfaceName);
                }
            }
        }
        return bean;
    }

    /** {@inheritDoc} */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        return bean;
    }

    /**
     * Store single instance of loggers but init if does not exist
     * 
     * @param targetMethod
     *            current processed method
     * @return singleton for class related to this execution
     */
    private Logger getLogger(Method targetMethod) {
        String methodName = targetMethod.getDeclaringClass().getCanonicalName();
        // Register logger if require
        if (!targetLoggers.containsKey(methodName)) {
            targetLoggers.put(methodName, LoggerFactory.getLogger(targetMethod.getDeclaringClass()));
        }
        return targetLoggers.get(methodName);
    }

    /**
     * Call if Flipped based on different parameters of the annotation
     * 
     * @param ff
     *            annotation over current method
     * @return if flippinf should be considere
     */
    private boolean shouldFlip(Flip ff) {
        boolean shouldFlip = false;
        if (ff.strategy() != NullType.class) {
            // Does this strategy has already be invoked ?
            String strategyClassName = ff.strategy().getCanonicalName();
            if (!strategySingletons.containsKey(strategyClassName)) {
                try {
                    strategySingletons.put(strategyClassName, (FlippingStrategy) ff.strategy().newInstance());
                } catch (InstantiationException e) {
                    throw new IllegalArgumentException("ff4j-aop: Cannot instanciate alterbean " + strategyClassName
                            + " please check default constructor existence & visibility", e);
                } catch (IllegalAccessException e) {
                    throw new IllegalArgumentException("ff4j-aop: Cannot instanciate alterbean " + strategyClassName
                            + " please check constructor visibility", e);
                }
            }
            FlippingStrategy targetStrategy = strategySingletons.get(strategyClassName);
            shouldFlip = getFf4j().checkOveridingStrategy(ff.name(), targetStrategy);
        } else {
            // no strategy, simple flip
            shouldFlip = getFf4j().check(ff.name());
        }
        return shouldFlip;
    }

    /**
     * Flip with alterBean is realized only if 'alterBean' property is filled and valid.
     * 
     * @param pMInvoc
     *            current method invocation
     * @param alterBean
     *            target bean to call
     * @param logger
     *            current logger for the class
     * @return flag if alterBean should be invoked
     */
    private boolean shouldCallAlterBeanMethod(final MethodInvocation pMInvoc, String alterBean, Logger logger) {
        boolean callAlterBeanMethod = false;
        Method method = pMInvoc.getMethod();
        Component component = pMInvoc.getThis().getClass().getAnnotation(Component.class);
        String currentBeanName = component.value();
        if (alterBean != null && !alterBean.isEmpty()) {
            if (alterBean.equals(currentBeanName)) {
                logger.debug("FeatureFlipping on method:{} class:{} already on the alterBean {}", method.getName(), method
                        .getDeclaringClass().getName(), alterBean);
            } else {
                if (!appCtx.containsBean(alterBean)) {
                    throw new BeanCreationException("ff4j-aop : bean name '" + alterBean
                            + "' has not been found in applicationContext still declared in 'alterBean' property of bean "
                            + method.getDeclaringClass());
                }
                callAlterBeanMethod = true;
            }
        }
        return callAlterBeanMethod;
    }

    private Object callAlterBeanMethod(final MethodInvocation pMInvoc, String alterBean, Logger targetLogger) {
        Method method = pMInvoc.getMethod();
        targetLogger.debug("FeatureFlipping on method:{} class:{}", method.getName(), method.getDeclaringClass().getName());
        // invoke same method (interface) with another spring bean (ff.alterBean())
        try {
            return method.invoke(appCtx.getBean(alterBean, method.getDeclaringClass()), pMInvoc.getArguments());
        } catch (Exception e) {
            throw new IllegalArgumentException("ff4j-aop: Cannot invoke method " + method.getName() + " on bean " + alterBean, e);
        }
    }

    private boolean shouldCallAlterClazzMethod(final MethodInvocation pMInvoc, Class<?> alterClass, Logger logger) {
        boolean callAlterBeanMethod = false;
        Method method = pMInvoc.getMethod();
        Class<?> currentClass = pMInvoc.getThis().getClass();
        if (alterClass != null && (alterClass != NullType.class)) {
            callAlterBeanMethod = !currentClass.equals(alterClass);
            if (!callAlterBeanMethod) {
                logger.debug("FeatureFlipping on method:{} class:{} already on the alterClazz {}", method.getName(), method
                        .getDeclaringClass().getName(), alterClass);
            }
        }
        return callAlterBeanMethod;
    }

    private Object callAlterClazzMethodOnFirst(final MethodInvocation pMInvoc, Flip ff, Logger targetLogger) {
        Map<String, ?> beans = appCtx.getBeansOfType(pMInvoc.getMethod().getDeclaringClass());
        for (Object bean : beans.values()) {
            if (isBeanAProxyOfAlterClass(bean, ff.alterClazz())) {
                return callAlterClazzMethod(pMInvoc, bean, targetLogger);
            }
        }
        throw new BeanCreationException("ff4j-aop : bean with class '" + ff.alterClazz()
                + "' has not been found in applicationContext still declared in 'alterClazz' property of bean "
                + pMInvoc.getMethod().getDeclaringClass());
    }

    private Object callAlterClazzMethod(final MethodInvocation pMInvoc, Object targetBean, Logger targetLogger) {
        Method method = pMInvoc.getMethod();
        String declaringClass = method.getDeclaringClass().getName();
        targetLogger.debug("FeatureFlipping on method:{} class:{}", method.getName(), declaringClass);
        try {
            return method.invoke(targetBean, pMInvoc.getArguments());
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException("ff4j-aop: Cannot invoke " + method.getName() + " on alterbean " + declaringClass
                    + " please check visibility", e);
        } catch (InvocationTargetException e) {
            throw new IllegalArgumentException("ff4j-aop: Cannot invoke " + method.getName() + " on alterbean " + declaringClass
                    + " please check signatures", e);
        }
    }

    protected boolean isBeanAProxyOfAlterClass(Object proxy, Class<?> alterClass) {
        if (AopUtils.isJdkDynamicProxy(proxy)) {
            try {
                return ((Advised) proxy).getTargetSource().getTarget().getClass().equals(alterClass);
            } catch (Exception e) {
                throw new IllegalArgumentException("ff4j-aop: Cannot evaluate is target bean is proxy", e);
            }
        } else {
            // expected to be cglib proxy then, which is simply a specialized class
            return proxy.getClass().equals(alterClass);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.appCtx = applicationContext;
    }

    /**
     * Getter accessor for attribute 'ff4j'.
     * 
     * @return current value of 'ff4j'
     */
    public FF4j getFf4j() {
        return ff4j;
    }

}