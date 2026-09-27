// Azure resources for the chat server: a Linux App Service plan and web app (Java 21), plus an
// Azure Web PubSub service used as the backplane between app instances.
//
// Defaults are the free tiers (App Service F1, Web PubSub Free_F1). Deploy with:
//   az deployment group create -g <resource-group> -f infra/main.bicep \
//     -p appName=<globally-unique-name> sessionSecret=<32+ random characters>

@description('Globally unique name; the site becomes https://<appName>.azurewebsites.net')
@minLength(2)
@maxLength(60)
param appName string

@description('Region for every resource.')
param location string = resourceGroup().location

@description('App Service plan SKU. F1 is free but runs a single instance; scaling out needs B1 or above (paid).')
@allowed([
  'F1'
  'B1'
  'B2'
  'S1'
  'P0v3'
])
param appServiceSku string = 'F1'

@description('Number of app instances. Must be 1 on F1.')
@minValue(1)
@maxValue(10)
param instanceCount int = 1

@description('Web PubSub SKU. Free_F1: 20 concurrent connections and 20,000 messages a day, no SLA.')
@allowed([
  'Free_F1'
  'Standard_S1'
])
param webPubSubSku string = 'Free_F1'

@description('HMAC key for session tokens, shared by every instance (at least 32 characters).')
@secure()
@minLength(32)
param sessionSecret string

@description('Optional PostgreSQL JDBC URL. Leave empty to use the H2 file database on /home (single instance only).')
param databaseUrl string = ''

@description('PostgreSQL user, when databaseUrl is set.')
param databaseUsername string = ''

@description('PostgreSQL password, when databaseUrl is set.')
@secure()
param databasePassword string = ''

var usePostgres = !empty(databaseUrl)
var hubName = 'chat'

resource webPubSub 'Microsoft.SignalRService/webPubSub@2024-03-01' = {
  name: '${appName}-wps'
  location: location
  sku: {
    name: webPubSubSku
    tier: webPubSubSku == 'Free_F1' ? 'Free' : 'Standard'
    capacity: 1
  }
  properties: {
    // Server instances authenticate with the access key (or a managed identity, see README).
    disableLocalAuth: false
    publicNetworkAccess: 'Enabled'
  }
}

resource plan 'Microsoft.Web/serverfarms@2023-12-01' = {
  name: '${appName}-plan'
  location: location
  kind: 'linux'
  sku: {
    name: appServiceSku
    capacity: instanceCount
  }
  properties: {
    reserved: true // required for Linux plans
  }
}

var baseSettings = [
  { name: 'SPRING_PROFILES_ACTIVE', value: usePostgres ? 'azure,postgres' : 'azure' }
  { name: 'SERVER_PORT', value: '80' }
  // Small instances: keep the heap within the plan's memory and use the lightweight collector.
  { name: 'JAVA_OPTS', value: '-XX:MaxRAMPercentage=70 -XX:+UseSerialGC' }
  { name: 'CHAT_SESSION_SECRET', value: sessionSecret }
  { name: 'CHAT_ALLOWED_ORIGINS', value: 'https://${appName}.azurewebsites.net' }
  { name: 'AZURE_WEBPUBSUB_CONNECTION_STRING', value: webPubSub.listKeys().primaryConnectionString }
  { name: 'AZURE_WEBPUBSUB_HUB', value: hubName }
]

var postgresSettings = usePostgres ? [
  { name: 'DATABASE_URL', value: databaseUrl }
  { name: 'DATABASE_USERNAME', value: databaseUsername }
  { name: 'DATABASE_PASSWORD', value: databasePassword }
] : []

resource site 'Microsoft.Web/sites@2023-12-01' = {
  name: appName
  location: location
  kind: 'app,linux'
  properties: {
    serverFarmId: plan.id
    httpsOnly: true
    siteConfig: {
      linuxFxVersion: 'JAVA|21-java21'
      webSocketsEnabled: true
      // Always On is not available on the Free tier; the app sleeps after about 20 idle minutes.
      alwaysOn: appServiceSku != 'F1'
      // Health checks ping every minute, which would keep a Free app awake and use up its
      // 60 CPU minutes a day, so they are only enabled on paid tiers.
      healthCheckPath: appServiceSku == 'F1' ? null : '/actuator/health/liveness'
      http20Enabled: true
      minTlsVersion: '1.2'
      ftpsState: 'Disabled'
      appSettings: concat(baseSettings, postgresSettings)
    }
  }
}

output url string = 'https://${site.properties.defaultHostName}'
output webPubSubHost string = webPubSub.properties.hostName
