package io.quarkus.agroal.runtime;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.Statement;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Singleton;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;

import org.jboss.logging.Logger;
import org.jboss.tm.XAResourceRecoveryRegistry;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.AgroalPoolInterceptor;
import io.agroal.api.configuration.AgroalConnectionPoolConfiguration.ConnectionValidator;
import io.agroal.api.configuration.AgroalConnectionPoolConfiguration.TransactionRequirement;
import io.agroal.api.configuration.AgroalDataSourceConfiguration;
import io.agroal.api.configuration.AgroalDataSourceConfiguration.DataSourceImplementation;
import io.agroal.api.configuration.supplier.AgroalConnectionFactoryConfigurationSupplier;
import io.agroal.api.configuration.supplier.AgroalConnectionPoolConfigurationSupplier;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import io.agroal.api.security.NamePrincipal;
import io.agroal.api.security.SimplePassword;
import io.agroal.api.transaction.TransactionIntegration;
import io.agroal.narayana.NarayanaTransactionIntegration;
import io.quarkus.agroal.runtime.JdbcDriver.JdbcDriverLiteral;
import io.quarkus.arc.Arc;
import io.quarkus.credentials.CredentialsProvider;
import io.quarkus.credentials.runtime.CredentialsProviderFinder;
import io.quarkus.datasource.common.runtime.DataSourceUtil;
import io.quarkus.datasource.runtime.DataSourceRuntimeConfig;
import io.quarkus.datasource.runtime.DataSourceSupport;
import io.quarkus.datasource.runtime.DataSourcesBuildTimeConfig;
import io.quarkus.datasource.runtime.DataSourcesRuntimeConfig;
import io.quarkus.narayana.jta.runtime.TransactionManagerConfiguration;

/**
 * This class is sort of a producer for {@link AgroalDataSource}.
 * <p>
 * It isn't a CDI producer in the literal sense, but it created a synthetic bean
 * from {@code AgroalProcessor}
 * The {@code createDataSource} method is called at runtime (see
 * {@link AgroalRecorder#agroalDataSourceSupplier(String, DataSourcesRuntimeConfig)})
 * in order to produce the actual {@code AgroalDataSource} objects.
 *
 * @deprecated This class should not be used from applications or other extensions.
 *             For applications, use CDI to retrieve datasources instead.
 *             For extensions, use {@link AgroalDataSourceUtil} instead.
 */
@Deprecated
@Singleton
public class DataSources {

    private static final Logger log = Logger.getLogger(DataSources.class.getName());

    private final DataSourcesBuildTimeConfig dataSourcesBuildTimeConfig;
    private final DataSourcesRuntimeConfig dataSourcesRuntimeConfig;
    private final DataSourcesJdbcBuildTimeConfig dataSourcesJdbcBuildTimeConfig;
    private final DataSourcesJdbcRuntimeConfig dataSourcesJdbcRuntimeConfig;
    private final TransactionManagerConfiguration transactionRuntimeConfig;
    private final TransactionManager transactionManager;
    private final XAResourceRecoveryRegistry xaResourceRecoveryRegistry;
    private final TransactionSynchronizationRegistry transactionSynchronizationRegistry;
    private final DataSourceSupport dataSourceSupport;
    private final AgroalDataSourceSupport agroalDataSourceSupport;
    private final Instance<AgroalPoolInterceptor> agroalPoolInterceptors;
    private final Instance<AgroalOpenTelemetryWrapper> agroalOpenTelemetryWrapper;

    private final ConcurrentMap<String, AgroalDataSource> dataSources = new ConcurrentHashMap<>();

    public DataSources(DataSourcesBuildTimeConfig dataSourcesBuildTimeConfig,
            DataSourcesRuntimeConfig dataSourcesRuntimeConfig, DataSourcesJdbcBuildTimeConfig dataSourcesJdbcBuildTimeConfig,
            DataSourcesJdbcRuntimeConfig dataSourcesJdbcRuntimeConfig,
            TransactionManagerConfiguration transactionRuntimeConfig,
            TransactionManager transactionManager,
            XAResourceRecoveryRegistry xaResourceRecoveryRegistry,
            TransactionSynchronizationRegistry transactionSynchronizationRegistry,
            DataSourceSupport dataSourceSupport,
            AgroalDataSourceSupport agroalDataSourceSupport,
            @Any Instance<AgroalPoolInterceptor> agroalPoolInterceptors,
            Instance<AgroalOpenTelemetryWrapper> agroalOpenTelemetryWrapper) {
        this.dataSourcesBuildTimeConfig = dataSourcesBuildTimeConfig;
        this.dataSourcesRuntimeConfig = dataSourcesRuntimeConfig;
        this.dataSourcesJdbcBuildTimeConfig = dataSourcesJdbcBuildTimeConfig;
        this.dataSourcesJdbcRuntimeConfig = dataSourcesJdbcRuntimeConfig;
        this.transactionRuntimeConfig = transactionRuntimeConfig;
        this.transactionManager = transactionManager;
        this.xaResourceRecoveryRegistry = xaResourceRecoveryRegistry;
        this.transactionSynchronizationRegistry = transactionSynchronizationRegistry;
        this.dataSourceSupport = dataSourceSupport;
        this.agroalDataSourceSupport = agroalDataSourceSupport;
        this.agroalPoolInterceptors = agroalPoolInterceptors;
        this.agroalOpenTelemetryWrapper = agroalOpenTelemetryWrapper;
    }

    /**
     * Meant to be used from recorders that create synthetic beans that need access to {@code Datasource}.
     * In such using {@code Arc.container.instance(DataSource.class)} is not possible because
     * {@code Datasource} is itself a synthetic bean.
     * <p>
     * This method relies on the fact that {@code DataSources} should - given the same input -
     * always return the same {@code AgroalDataSource} no matter how many times it is invoked
     * (which makes sense because {@code DataSource} is a {@code Singleton} bean).
     * <p>
     * This method is thread-safe
     *
     * @deprecated This method should not be used as it can very easily lead to timing issues during bean creation
     */
    @Deprecated
    public static AgroalDataSource fromName(String dataSourceName) {
        return Arc.container().instance(DataSources.class).get()
                .getDataSource(dataSourceName);
    }

    public boolean isDataSourceCreated(String dataSourceName) {
        return dataSources.containsKey(dataSourceName);
    }

    public Set<String> getActiveDataSourceNames() {
        // Datasources are created on startup,
        // and we only create active datasources.
        return dataSources.keySet();
    }

    public AgroalDataSource getDataSource(String dataSourceName) {
        return dataSources.computeIfAbsent(dataSourceName, new Function<String, AgroalDataSource>() {
            @Override
            public AgroalDataSource apply(String s) {
                return doCreateDataSource(s, true);
            }
        });
    }

    @PostConstruct
    public void start() {
        for (String dataSourceName : agroalDataSourceSupport.entries.keySet()) {
            dataSources.computeIfAbsent(dataSourceName, new Function<String, AgroalDataSource>() {
                @Override
                public AgroalDataSource apply(String s) {
                    return doCreateDataSource(s, false);
                }
            });
        }
    }

    @SuppressWarnings("resource")
    public AgroalDataSource doCreateDataSource(String dataSourceName, boolean failIfInactive) {
        if (!agroalDataSourceSupport.entries.containsKey(dataSourceName)) {
            throw new IllegalArgumentException("No datasource named '" + dataSourceName + "' exists");
        }

        DataSourceJdbcBuildTimeConfig dataSourceJdbcBuildTimeConfig = dataSourcesJdbcBuildTimeConfig
                .dataSources().get(dataSourceName).jdbc();
        DataSourceRuntimeConfig dataSourceRuntimeConfig = dataSourcesRuntimeConfig.dataSources().get(dataSourceName);

        if (dataSourceSupport.getInactiveNames().contains(dataSourceName)) {
            if (failIfInactive) {
                throw DataSourceUtil.dataSourceInactive(dataSourceName);
            } else {
                // This only happens on startup, and effectively cancels the creation
                // so that we only throw an exception on first actual use.
                return null;
            }
        }

        DataSourceJdbcRuntimeConfig dataSourceJdbcRuntimeConfig = dataSourcesJdbcRuntimeConfig
                .dataSources().get(dataSourceName).jdbc();
        if (!dataSourceJdbcRuntimeConfig.url().isPresent()) {
            //this is not an error situation, because we want to allow the situation where a JDBC extension
            //is installed but has not been configured
            return new UnconfiguredDataSource(
                    DataSourceUtil.dataSourcePropertyKey(dataSourceName, "jdbc.url") + " has not been defined");
        }

        // we first make sure that all available JDBC drivers are loaded in the current TCCL
        loadDriversInTCCL();

        AgroalDataSourceSupport.Entry matchingSupportEntry = agroalDataSourceSupport.entries.get(dataSourceName);
        String resolvedDriverClass = matchingSupportEntry.resolvedDriverClass;
        Class<?> driver;
        try {
            driver = Class.forName(resolvedDriverClass, true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(
                    "Unable to load the datasource driver " + resolvedDriverClass + " for datasource " + dataSourceName, e);
        }

        String jdbcUrl = dataSourceJdbcRuntimeConfig.url().get();

        String resolvedDbKind = matchingSupportEntry.resolvedDbKind;
        AgroalConnectionConfigurer agroalConnectionConfigurer = Arc.container()
                .instance(AgroalConnectionConfigurer.class, new JdbcDriverLiteral(resolvedDbKind))
                .orElse(new UnknownDbAgroalConnectionConfigurer());

        AgroalDataSourceConfigurationSupplier dataSourceConfiguration = new AgroalDataSourceConfigurationSupplier();

        // Set pool-less mode
        if (!dataSourceJdbcRuntimeConfig.poolingEnabled()) {
            dataSourceConfiguration.dataSourceImplementation(DataSourceImplementation.AGROAL_POOLLESS);
        }

        AgroalConnectionPoolConfigurationSupplier poolConfiguration = dataSourceConfiguration.connectionPoolConfiguration();
        AgroalConnectionFactoryConfigurationSupplier connectionFactoryConfiguration = poolConfiguration
                .connectionFactoryConfiguration();

        boolean mpMetricsPresent = agroalDataSourceSupport.mpMetricsPresent;
        applyNewConfiguration(dataSourceName, dataSourceConfiguration, poolConfiguration, connectionFactoryConfiguration,
                driver, jdbcUrl,
                dataSourceJdbcBuildTimeConfig, dataSourceRuntimeConfig, dataSourceJdbcRuntimeConfig, transactionRuntimeConfig,
                mpMetricsPresent);

        if (agroalDataSourceSupport.disableSslSupport) {
            agroalConnectionConfigurer.disableSslSupport(resolvedDbKind, dataSourceConfiguration);
        }
        //we use a custom cache for two reasons:
        //fast thread local cache should be faster
        //and it prevents a thread local leak
        try {
            Class.forName("io.netty.util.concurrent.FastThreadLocal", true, Thread.currentThread().getContextClassLoader());
            dataSourceConfiguration.connectionPoolConfiguration().connectionCache(new QuarkusNettyConnectionCache());
        } catch (ClassNotFoundException e) {
            dataSourceConfiguration.connectionPoolConfiguration().connectionCache(new QuarkusSimpleConnectionCache());
        }

        agroalConnectionConfigurer.setExceptionSorter(resolvedDbKind, dataSourceConfiguration);

        // Explicit reference to bypass reflection need of the ServiceLoader used by AgroalDataSource#from
        AgroalDataSourceConfiguration agroalConfiguration = dataSourceConfiguration.get();
        AgroalDataSource dataSource = new io.agroal.pool.DataSource(agroalConfiguration,
                new AgroalEventLoggingListener(dataSourceName,
                        agroalConfiguration.connectionPoolConfiguration()
                                .transactionRequirement() == TransactionRequirement.WARN));
        log.debugv("Started datasource {0} connected to {1}", dataSourceName,
                agroalConfiguration.connectionPoolConfiguration().connectionFactoryConfiguration().jdbcUrl());

        // Set pool interceptors for this datasource
        Collection<AgroalPoolInterceptor> interceptorList = agroalPoolInterceptors
                .select(AgroalDataSourceUtil.qualifier(dataSourceName))
                .stream().collect(Collectors.toList());
        if (!interceptorList.isEmpty()) {
            dataSource.setPoolInterceptors(interceptorList);
        }

        if (dataSourceJdbcBuildTimeConfig.telemetry() && dataSourceJdbcRuntimeConfig.telemetry().orElse(true)) {
            // activate OpenTelemetry JDBC instrumentation by wrapping AgroalDatasource
            // use an optional CDI bean as we can't reference optional OpenTelemetry classes here
            dataSource = agroalOpenTelemetryWrapper.get().apply(dataSource);
        }

        return dataSource;
    }

    private void applyNewConfiguration(String dataSourceName, AgroalDataSourceConfigurationSupplier dataSourceConfiguration,
            AgroalConnectionPoolConfigurationSupplier poolConfiguration,
            AgroalConnectionFactoryConfigurationSupplier connectionFactoryConfiguration, Class<?> driver, String jdbcUrl,
            DataSourceJdbcBuildTimeConfig dataSourceJdbcBuildTimeConfig, DataSourceRuntimeConfig dataSourceRuntimeConfig,
            DataSourceJdbcRuntimeConfig dataSourceJdbcRuntimeConfig, TransactionManagerConfiguration transactionRuntimeConfig,
            boolean mpMetricsPresent) {
        connectionFactoryConfiguration.jdbcUrl(jdbcUrl);
        connectionFactoryConfiguration.connectionProviderClass(driver);
        connectionFactoryConfiguration.trackJdbcResources(dataSourceJdbcRuntimeConfig.detectStatementLeaks());

        if (dataSourceJdbcRuntimeConfig.transactionIsolationLevel().isPresent()) {
            connectionFactoryConfiguration
                    .jdbcTransactionIsolation(
                            dataSourceJdbcRuntimeConfig.transactionIsolationLevel().get());
        }

        if (dataSourceJdbcBuildTimeConfig.transactions() != io.quarkus.agroal.runtime.TransactionIntegration.DISABLED) {
            TransactionIntegration txIntegration = new NarayanaTransactionIntegration(transactionManager,
                    transactionSynchronizationRegistry, null, false,
                    dataSourceJdbcBuildTimeConfig.transactions() == io.quarkus.agroal.runtime.TransactionIntegration.XA
                            && transactionRuntimeConfig.enableRecovery
                                    ? xaResourceRecoveryRegistry
                                    : null);
            if (dataSourceJdbcBuildTimeConfig.transactions() == io.quarkus.agroal.runtime.TransactionIntegration.XA
                    && !transactionRuntimeConfig.enableRecovery) {
                log.warnv(
                        "Datasource {0} enables XA but transaction recovery is not enabled. Please enable transaction recovery by setting quarkus.transaction-manager.enable-recovery=true, otherwise data may be lost if the application is terminated abruptly",
                        dataSourceName);
            }
            poolConfiguration.transactionIntegration(txIntegration);
        }

        // New connection SQL
        if (dataSourceJdbcRuntimeConfig.newConnectionSql().isPresent()) {
            connectionFactoryConfiguration.initialSql(dataSourceJdbcRuntimeConfig.newConnectionSql().get());
        }

        // metrics
        if (dataSourceJdbcBuildTimeConfig.enableMetrics().isPresent()) {
            dataSourceConfiguration.metricsEnabled(dataSourceJdbcBuildTimeConfig.enableMetrics().get());
        } else {
            // if the enable-metrics property is unspecified, treat it as true if MP Metrics are being exposed
            dataSourceConfiguration.metricsEnabled(dataSourcesBuildTimeConfig.metricsEnabled() && mpMetricsPresent);
        }

        // Authentication
        if (dataSourceRuntimeConfig.username().isPresent()) {
            NamePrincipal username = new NamePrincipal(dataSourceRuntimeConfig.username().get());
            connectionFactoryConfiguration
                    .principal(username).recoveryPrincipal(username);
        }
        if (dataSourceRuntimeConfig.password().isPresent()) {
            SimplePassword password = new SimplePassword(dataSourceRuntimeConfig.password().get());
            connectionFactoryConfiguration
                    .credential(password).recoveryCredential(password);
        }

        // credentials provider
        if (dataSourceRuntimeConfig.credentialsProvider().isPresent()) {
            String beanName = dataSourceRuntimeConfig.credentialsProviderName().orElse(null);
            CredentialsProvider credentialsProvider = CredentialsProviderFinder.find(beanName);
            String name = dataSourceRuntimeConfig.credentialsProvider().get();
            connectionFactoryConfiguration
                    .credential(new AgroalVaultCredentialsProviderPassword(name, credentialsProvider));
        }

        // Extra JDBC properties
        for (Map.Entry<String, String> entry : dataSourceJdbcRuntimeConfig.additionalJdbcProperties().entrySet()) {
            connectionFactoryConfiguration.jdbcProperty(entry.getKey(), entry.getValue());
        }

        // Pool size configuration:
        poolConfiguration.minSize(dataSourceJdbcRuntimeConfig.minSize());
        poolConfiguration.maxSize(dataSourceJdbcRuntimeConfig.maxSize());
        if (dataSourceJdbcRuntimeConfig.initialSize().isPresent() && dataSourceJdbcRuntimeConfig.initialSize().getAsInt() > 0) {
            poolConfiguration.initialSize(dataSourceJdbcRuntimeConfig.initialSize().getAsInt());
        }

        // Connection management
        poolConfiguration.connectionValidator(ConnectionValidator.defaultValidator());
        if (dataSourceJdbcRuntimeConfig.acquisitionTimeout().isPresent()) {
            poolConfiguration.acquisitionTimeout(dataSourceJdbcRuntimeConfig.acquisitionTimeout().get());
        }
        poolConfiguration.validationTimeout(dataSourceJdbcRuntimeConfig.backgroundValidationInterval());
        if (dataSourceJdbcRuntimeConfig.foregroundValidationInterval().isPresent()) {
            poolConfiguration.idleValidationTimeout(dataSourceJdbcRuntimeConfig.foregroundValidationInterval().get());
        }
        if (dataSourceJdbcRuntimeConfig.validationQuerySql().isPresent()) {
            String validationQuery = dataSourceJdbcRuntimeConfig.validationQuerySql().get();
            poolConfiguration.connectionValidator(new ConnectionValidator() {

                @Override
                public boolean isValid(Connection connection) {
                    try (Statement stmt = connection.createStatement()) {
                        stmt.execute(validationQuery);
                        return true;
                    } catch (Exception e) {
                        log.warn("Connection validation failed", e);
                    }
                    return false;
                }
            });
        }
        poolConfiguration.validateOnBorrow(dataSourceJdbcRuntimeConfig.validateOnBorrow());
        poolConfiguration.reapTimeout(dataSourceJdbcRuntimeConfig.idleRemovalInterval());
        if (dataSourceJdbcRuntimeConfig.leakDetectionInterval().isPresent()) {
            poolConfiguration.leakTimeout(dataSourceJdbcRuntimeConfig.leakDetectionInterval().get());
        }
        if (dataSourceJdbcRuntimeConfig.maxLifetime().isPresent()) {
            poolConfiguration.maxLifetime(dataSourceJdbcRuntimeConfig.maxLifetime().get());
        }
        if (dataSourceJdbcRuntimeConfig.transactionRequirement().isPresent()) {
            poolConfiguration.transactionRequirement(dataSourceJdbcRuntimeConfig.transactionRequirement().get());
        }
        poolConfiguration.enhancedLeakReport(dataSourceJdbcRuntimeConfig.extendedLeakReport());
        poolConfiguration.flushOnClose(dataSourceJdbcRuntimeConfig.flushOnClose());
    }

    /**
     * Uses the {@link ServiceLoader#load(Class) ServiceLoader to load the JDBC drivers} in context
     * of the current {@link Thread#getContextClassLoader() TCCL}
     */
    private static void loadDriversInTCCL() {
        // load JDBC drivers in the current TCCL
        final ServiceLoader<Driver> drivers = ServiceLoader.load(Driver.class);
        final Iterator<Driver> iterator = drivers.iterator();
        while (iterator.hasNext()) {
            try {
                // load the driver
                iterator.next();
            } catch (Throwable t) {
                // ignore
            }
        }
    }

    @PreDestroy
    public void stop() {
        for (AgroalDataSource dataSource : dataSources.values()) {
            if (dataSource != null) {
                dataSource.close();
            }
        }
    }
}
