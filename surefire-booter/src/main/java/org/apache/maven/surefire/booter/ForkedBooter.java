package org.apache.maven.surefire.booter;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.maven.surefire.providerapi.ProviderParameters;
import org.apache.maven.surefire.providerapi.SurefireProvider;
import org.apache.maven.surefire.report.LegacyPojoStackTraceWriter;
import org.apache.maven.surefire.report.ReporterFactory;
import org.apache.maven.surefire.report.StackTraceWriter;
import org.apache.maven.surefire.suite.RunResult;
import org.apache.maven.surefire.testset.TestSetFailedException;

import static org.apache.maven.surefire.booter.Shutdown.DEFAULT;
import static org.apache.maven.surefire.booter.Shutdown.KILL;
import static org.apache.maven.surefire.booter.ForkingRunListener.BOOTERCODE_BYE;
import static org.apache.maven.surefire.booter.ForkingRunListener.BOOTERCODE_ERROR;
import static org.apache.maven.surefire.booter.ForkingRunListener.encode;
import static org.apache.maven.surefire.util.ReflectionUtils.instantiateOneArg;
import static org.apache.maven.surefire.util.internal.DaemonThreadFactory.newDaemonThreadFactory;
import static org.apache.maven.surefire.util.internal.StringUtils.encodeStringForForkCommunication;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * The part of the booter that is unique to a forked vm.
 * <p/>
 * Deals with deserialization of the booter wire-level protocol
 * <p/>
 *
 * @author Jason van Zyl
 * @author Emmanuel Venisse
 * @author Kristian Rosenvold
 */
public final class ForkedBooter
{
    private static final long SYSTEM_EXIT_TIMEOUT_IN_SECONDS = 30;
    private static final long PING_TIMEOUT_IN_SECONDS = 20;

    private static final ScheduledExecutorService JVM_TERMINATOR = createJvmTerminator();

    /**
     * This method is invoked when Surefire is forked - this method parses and organizes the arguments passed to it and
     * then calls the Surefire class' run method. <p/> The system exit code will be 1 if an exception is thrown.
     *
     * @param args Commandline arguments
     */
    public static void main( String... args )
    {
        final MasterProcessReader reader = startupMasterProcessReader();
        final ScheduledFuture<?> pingScheduler = listenToShutdownCommands( reader );
        final PrintStream originalOut = System.out;
        try
        {
            if ( args.length > 1 )
            {
                SystemPropertyManager.setSystemProperties( new File( args[1] ) );
            }

            File surefirePropertiesFile = new File( args[0] );
            InputStream stream = surefirePropertiesFile.exists() ? new FileInputStream( surefirePropertiesFile ) : null;
            BooterDeserializer booterDeserializer = new BooterDeserializer( stream );
            ProviderConfiguration providerConfiguration = booterDeserializer.deserialize();
            final StartupConfiguration startupConfiguration = booterDeserializer.getProviderConfiguration();

            TypeEncodedValue forkedTestSet = providerConfiguration.getTestForFork();
            boolean readTestsFromInputStream = providerConfiguration.isReadTestsFromInStream();

            final ClasspathConfiguration classpathConfiguration = startupConfiguration.getClasspathConfiguration();
            if ( startupConfiguration.isManifestOnlyJarRequestedAndUsable() )
            {
                classpathConfiguration.trickClassPathWhenManifestOnlyClasspath();
            }

            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            classLoader.setDefaultAssertionStatus( classpathConfiguration.isEnableAssertions() );
            startupConfiguration.writeSurefireTestClasspathProperty();

            final Object testSet;
            if ( forkedTestSet != null )
            {
                testSet = forkedTestSet.getDecodedValue( classLoader );
            }
            else if ( readTestsFromInputStream )
            {
                testSet = new LazyTestsToRun( originalOut );
            }
            else
            {
                testSet = null;
            }

            try
            {
                runSuitesInProcess( testSet, startupConfiguration, providerConfiguration, originalOut );
            }
            catch ( InvocationTargetException t )
            {
                LegacyPojoStackTraceWriter stackTraceWriter =
                    new LegacyPojoStackTraceWriter( "test subystem", "no method", t.getTargetException() );
                StringBuilder stringBuilder = new StringBuilder();
                encode( stringBuilder, stackTraceWriter, false );
                encodeAndWriteToOutput( ( (char) BOOTERCODE_ERROR ) + ",0," + stringBuilder + "\n" , originalOut );
            }
            catch ( Throwable t )
            {
                StackTraceWriter stackTraceWriter = new LegacyPojoStackTraceWriter( "test subystem", "no method", t );
                StringBuilder stringBuilder = new StringBuilder();
                encode( stringBuilder, stackTraceWriter, false );
                encodeAndWriteToOutput( ( (char) BOOTERCODE_ERROR ) + ",0," + stringBuilder + "\n", originalOut );
            }
            // Say bye.
            encodeAndWriteToOutput( ( (char) BOOTERCODE_BYE ) + ",0,BYE!\n", originalOut );
            originalOut.flush();
            // noinspection CallToSystemExit
            exit( 0, DEFAULT, reader );
        }
        catch ( Throwable t )
        {
            // Just throwing does getMessage() and a local trace - we want to call printStackTrace for a full trace
            // noinspection UseOfSystemOutOrSystemErr
            t.printStackTrace( System.err );
            // noinspection ProhibitedExceptionThrown,CallToSystemExit
            exit( 1, DEFAULT, reader );
        }
        finally
        {
            pingScheduler.cancel( true );
        }
    }

    private static MasterProcessReader startupMasterProcessReader()
    {
        return MasterProcessReader.getReader();
    }

    private static ScheduledFuture<?> listenToShutdownCommands( MasterProcessReader reader )
    {
        reader.addShutdownListener( createExitHandler( reader ) );
        AtomicBoolean pingDone = new AtomicBoolean( true );
        reader.addNoopListener( createPingHandler( pingDone ) );
        return JVM_TERMINATOR.scheduleAtFixedRate( createPingJob( pingDone, reader ),
                                                   0, PING_TIMEOUT_IN_SECONDS, SECONDS );
    }

    private static MasterProcessListener createPingHandler( final AtomicBoolean pingDone )
    {
        return new MasterProcessListener()
        {
            public void update( Command command )
            {
                pingDone.set( true );
            }
        };
    }

    private static MasterProcessListener createExitHandler( final MasterProcessReader reader )
    {
        return new MasterProcessListener()
        {
            public void update( Command command )
            {
                exit( 1, command.toShutdownData(), reader );
            }
        };
    }

    private static Runnable createPingJob( final AtomicBoolean pingDone, final MasterProcessReader reader  )
    {
        return new Runnable()
        {
            public void run()
            {
                boolean hasPing = pingDone.getAndSet( false );
                if ( !hasPing )
                {
                    exit( 1, KILL, reader );
                }
            }
        };
    }

    private static void encodeAndWriteToOutput( String string, PrintStream out )
    {
        byte[] encodeBytes = encodeStringForForkCommunication( string );
        out.write( encodeBytes, 0, encodeBytes.length );
    }

    private static void exit( int returnCode, Shutdown shutdownType, MasterProcessReader reader )
    {
        reader.stop();
        switch ( shutdownType )
        {
            case KILL:
                Runtime.getRuntime().halt( returnCode );
            case EXIT:
                launchLastDitchDaemonShutdownThread( returnCode );
                System.exit( returnCode );
            case DEFAULT:
            default:
                break;
        }
    }

    private static RunResult runSuitesInProcess( Object testSet, StartupConfiguration startupConfiguration,
                                                 ProviderConfiguration providerConfiguration,
                                                 PrintStream originalSystemOut )
        throws SurefireExecutionException, TestSetFailedException, InvocationTargetException
    {
        final ReporterFactory factory = createForkingReporterFactory( providerConfiguration, originalSystemOut );

        return invokeProviderInSameClassLoader( testSet, factory, providerConfiguration, true, startupConfiguration,
                                                false );
    }

    private static ReporterFactory createForkingReporterFactory( ProviderConfiguration providerConfiguration,
                                                                 PrintStream originalSystemOut )
    {
        final boolean trimStackTrace = providerConfiguration.getReporterConfiguration().isTrimStackTrace();
        return SurefireReflector.createForkingReporterFactoryInCurrentClassLoader( trimStackTrace, originalSystemOut );
    }

    private static ScheduledExecutorService createJvmTerminator()
    {
        ThreadFactory threadFactory = newDaemonThreadFactory( "last-ditch-daemon-shutdown-thread-"
                                                            + SYSTEM_EXIT_TIMEOUT_IN_SECONDS
                                                            + "sec" );

        return Executors.newScheduledThreadPool( 1, threadFactory );
    }

    @SuppressWarnings( "checkstyle:emptyblock" )
    private static void launchLastDitchDaemonShutdownThread( final int returnCode )
    {
            JVM_TERMINATOR.schedule( new Runnable()
            {
                public void run()
                {
                    Runtime.getRuntime().halt( returnCode );
                }
            }, SYSTEM_EXIT_TIMEOUT_IN_SECONDS, SECONDS );
    }

    private static RunResult invokeProviderInSameClassLoader( Object testSet, Object factory,
                                                             ProviderConfiguration providerConfiguration,
                                                             boolean insideFork,
                                                             StartupConfiguration startupConfig,
                                                             boolean restoreStreams )
        throws TestSetFailedException, InvocationTargetException
    {
        final PrintStream orgSystemOut = System.out;
        final PrintStream orgSystemErr = System.err;
        // Note that System.out/System.err are also read in the "ReporterConfiguration" instatiation
        // in createProvider below. These are the same values as here.

        try
        {
            return createProviderInCurrentClassloader( startupConfig, insideFork, providerConfiguration, factory )
                .invoke( testSet );
        }
        finally
        {
            if ( restoreStreams && System.getSecurityManager() == null )
            {
                System.setOut( orgSystemOut );
                System.setErr( orgSystemErr );
            }
        }
    }

    private static SurefireProvider createProviderInCurrentClassloader( StartupConfiguration startupConfiguration1,
                                                                       boolean isInsideFork,
                                                                       ProviderConfiguration providerConfiguration,
                                                                       Object reporterManagerFactory1 )
    {
        BaseProviderFactory bpf = new BaseProviderFactory( (ReporterFactory) reporterManagerFactory1, isInsideFork );
        bpf.setTestRequest( providerConfiguration.getTestSuiteDefinition() );
        bpf.setReporterConfiguration( providerConfiguration.getReporterConfiguration() );
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        bpf.setClassLoaders( classLoader );
        bpf.setTestArtifactInfo( providerConfiguration.getTestArtifact() );
        bpf.setProviderProperties( providerConfiguration.getProviderProperties() );
        bpf.setRunOrderParameters( providerConfiguration.getRunOrderParameters() );
        bpf.setDirectoryScannerParameters( providerConfiguration.getDirScannerParams() );
        bpf.setMainCliOptions( providerConfiguration.getMainCliOptions() );
        bpf.setSkipAfterFailureCount( providerConfiguration.getSkipAfterFailureCount() );
        bpf.setShutdown( providerConfiguration.getShutdown() );
        String providerClass = startupConfiguration1.getActualClassName();
        return (SurefireProvider) instantiateOneArg( classLoader, providerClass, ProviderParameters.class, bpf );
    }
}
