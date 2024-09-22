package io.hops.kafka;

import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.requests.RequestContext;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.common.security.auth.KafkaPrincipal;
import org.apache.kafka.server.authorizer.Action;
import org.apache.kafka.server.authorizer.AuthorizationResult;
import org.javatuples.Pair;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

public class TestHopsAclAuthorizer {

  private DbConnection dbConnection;
  private LoadingCache<String, Integer> topicProjectCache;
  private LoadingCache<String, Pair<Integer, String>> userProjectCache;
  private LoadingCache<Pair<Integer, Integer>, String> projectShareCache;
  private HopsAclAuthorizer hopsAclAuthorizer;

  @BeforeEach
  public void setup() {
    topicProjectCache = Mockito.mock(LoadingCache.class);
    userProjectCache = Mockito.mock(LoadingCache.class);
    projectShareCache = Mockito.mock(LoadingCache.class);
    dbConnection = Mockito.mock(DbConnection.class);

    hopsAclAuthorizer = new HopsAclAuthorizer(topicProjectCache, userProjectCache, projectShareCache, dbConnection);
  }

  @Test
  public void testAuthorizeRejectAnonymous() throws UnknownHostException {
    // Arrange
    Action action = buildAction("describe", "TOPIC", "test");
    RequestContext requestContext = buildRequestContext(KafkaPrincipal.ANONYMOUS);

    // Act
    AuthorizationResult authorizationResult = hopsAclAuthorizer.authorize(requestContext, Arrays.asList(action)).get(0);

    // Assert
    Assertions.assertEquals(AuthorizationResult.DENIED, authorizationResult);
  }

  @Test
  public void testAuthorizeSuperUser() throws UnknownHostException {
    // Arrange
    KafkaPrincipal kafkaPrincipal = new KafkaPrincipal("User", "sudo");
    hopsAclAuthorizer.setSuperUsers(kafkaPrincipal.getPrincipalType() + ":" + kafkaPrincipal.getName());

    Action action = buildAction("describe", "TOPIC", "test");
    RequestContext requestContext = buildRequestContext(kafkaPrincipal);

    // Act
    AuthorizationResult authorizationResult = hopsAclAuthorizer.authorize(requestContext, Arrays.asList(action)).get(0);

    // Assert
    Assertions.assertEquals(AuthorizationResult.ALLOWED, authorizationResult);
  }

  @Test
  public void testAuthorizeMissingTopic() throws UnknownHostException, ExecutionException {
    // Arrange
    Mockito.when(topicProjectCache.get(anyString())).thenThrow(new CacheLoader.InvalidCacheLoadException(""));

    Action action = buildAction("describe", "TOPIC", "test");

    // Act
    AuthorizationResult authorizationResult = hopsAclAuthorizer.authorize("project__user", action);

    // Assert
    Assertions.assertEquals(AuthorizationResult.DENIED, authorizationResult);
    Mockito.verify(topicProjectCache, Mockito.times(1)).get(anyString());
    Mockito.verify(userProjectCache, Mockito.times(0)).get(anyString());
    Mockito.verify(projectShareCache, Mockito.times(0)).get(any());
  }

  @Test
  public void testAuthorizeTopicException() throws UnknownHostException, ExecutionException {
    // Arrange
    Mockito.when(topicProjectCache.get(anyString())).thenThrow(new ExecutionException(new SQLException()));

    Action action = buildAction("describe", "TOPIC", "test");

    // Act
    AuthorizationResult authorizationResult = hopsAclAuthorizer.authorize("project__user", action);

    // Assert
    Assertions.assertEquals(AuthorizationResult.DENIED, authorizationResult);
    Mockito.verify(topicProjectCache, Mockito.times(2)).get(anyString());
    Mockito.verify(userProjectCache, Mockito.times(0)).get(anyString());
    Mockito.verify(projectShareCache, Mockito.times(0)).get(any());
  }

  @Test
  public void testAuthorizeMissingPrincipal() throws UnknownHostException, ExecutionException {
    // Arrange
    Mockito.when(topicProjectCache.get(anyString())).thenReturn(120);
    Mockito.when(userProjectCache.get(anyString())).thenThrow(new CacheLoader.InvalidCacheLoadException(""));

    Action action = buildAction("describe", "TOPIC", "test");

    // Act
    AuthorizationResult authorizationResult = hopsAclAuthorizer.authorize("project__user", action);

    // Assert
    Assertions.assertEquals(AuthorizationResult.DENIED, authorizationResult);
    Mockito.verify(topicProjectCache, Mockito.times(1)).get(anyString());
    Mockito.verify(userProjectCache, Mockito.times(1)).get(anyString());
    Mockito.verify(projectShareCache, Mockito.times(0)).get(any());
  }

  @Test
  public void testAuthorizePrincipalException() throws UnknownHostException, ExecutionException {
    // Arrange
    Mockito.when(topicProjectCache.get(anyString())).thenReturn(120);
    Mockito.when(userProjectCache.get(anyString())).thenThrow(new ExecutionException(new SQLException()));

    Action action = buildAction("describe", "TOPIC", "test");

    // Act
    AuthorizationResult authorizationResult = hopsAclAuthorizer.authorize("project__user", action);

    // Assert
    Assertions.assertEquals(AuthorizationResult.DENIED, authorizationResult);
    Mockito.verify(topicProjectCache, Mockito.times(2)).get(anyString());
    Mockito.verify(userProjectCache, Mockito.times(2)).get(anyString());
    Mockito.verify(projectShareCache, Mockito.times(0)).get(any());
  }

  @Test
  public void testAuthorizeMissingShared() throws UnknownHostException, ExecutionException {
    // Arrange
    Mockito.when(topicProjectCache.get(anyString())).thenReturn(120);
    Mockito.when(userProjectCache.get(anyString())).thenReturn(new Pair<>(119, Consts.DATA_OWNER));
    Mockito.when(projectShareCache.get(any())).thenThrow(new CacheLoader.InvalidCacheLoadException(""));

    Action action = buildAction("describe", "TOPIC", "test");

    // Act
    AuthorizationResult authorizationResult = hopsAclAuthorizer.authorize("project__user", action);

    // Assert
    Assertions.assertEquals(AuthorizationResult.DENIED, authorizationResult);
    Mockito.verify(topicProjectCache, Mockito.times(1)).get(anyString());
    Mockito.verify(userProjectCache, Mockito.times(1)).get(anyString());
    Mockito.verify(projectShareCache, Mockito.times(1)).get(any());
  }

  @Test
  public void testAuthorizeSharedException() throws UnknownHostException, ExecutionException {
    // Arrange
    Mockito.when(topicProjectCache.get(anyString())).thenReturn(120);
    Mockito.when(userProjectCache.get(anyString())).thenReturn(new Pair<>(119, Consts.DATA_OWNER));
    Mockito.when(projectShareCache.get(any())).thenThrow(new ExecutionException(new SQLException()));

    Action action = buildAction("describe", "TOPIC", "test");

    // Act
    AuthorizationResult authorizationResult = hopsAclAuthorizer.authorize("project__user", action);

    // Assert
    Assertions.assertEquals(AuthorizationResult.DENIED, authorizationResult);
    Mockito.verify(topicProjectCache, Mockito.times(2)).get(anyString());
    Mockito.verify(userProjectCache, Mockito.times(2)).get(anyString());
    Mockito.verify(projectShareCache, Mockito.times(2)).get(any());
  }

  @ParameterizedTest
  @CsvSource({
      // TOPIC (project matters only for topics)
        // Same project
      "119, 119, Data owner,      WRITE,  TOPIC,    , ALLOWED",
      "119, 119, Data scientist,  WRITE,  TOPIC,    , DENIED",
      "119, 119, Data owner,      CREATE,  TOPIC,    , ALLOWED",
      "119, 119, Data scientist,  CREATE,  TOPIC,    , DENIED",
      "119, 119, Data owner,      READ,  TOPIC,     , ALLOWED",
      "119, 119, Data scientist,  READ,  TOPIC,     , ALLOWED",
      "119, 119, Data owner,      DESCRIBE,  TOPIC, , ALLOWED",
      "119, 119, Data scientist,  DESCRIBE,  TOPIC, , ALLOWED",
        // Shared with READ_ONLY permission
      "119, 120, Data owner,      WRITE,  TOPIC,    READ_ONLY,  DENIED",
      "119, 120, Data scientist,  WRITE,  TOPIC,    READ_ONLY,  DENIED",
      "119, 120, Data owner,      CREATE,  TOPIC,    READ_ONLY,  DENIED",
      "119, 120, Data scientist,  CREATE,  TOPIC,    READ_ONLY,  DENIED",
      "119, 120, Data owner,      READ,  TOPIC,     READ_ONLY,  ALLOWED",
      "119, 120, Data scientist,  READ,  TOPIC,     READ_ONLY,  ALLOWED",
      "119, 120, Data owner,      DESCRIBE,  TOPIC, READ_ONLY,  ALLOWED",
      "119, 120, Data scientist,  DESCRIBE,  TOPIC, READ_ONLY,  ALLOWED",
        // Shared with not supported permission
      "119, 120, Data owner,      WRITE,  TOPIC,    EDITABLE,   DENIED",
      "119, 120, Data scientist,  WRITE,  TOPIC,    EDITABLE,   DENIED",
      "119, 120, Data owner,      CREATE,  TOPIC,    EDITABLE,   DENIED",
      "119, 120, Data scientist,  CREATE,  TOPIC,    EDITABLE,   DENIED",
      "119, 120, Data owner,      READ,  TOPIC,     EDITABLE,   DENIED",
      "119, 120, Data scientist,  READ,  TOPIC,     EDITABLE,   DENIED",
      "119, 120, Data owner,      DESCRIBE,  TOPIC, EDITABLE,   DENIED",
      "119, 120, Data scientist,  DESCRIBE,  TOPIC, EDITABLE,   DENIED",
      // GROUP
      "119, 119, Data owner,      READ,  GROUP,     , ALLOWED",
      "119, 119, Data scientist,  READ,  GROUP,     , ALLOWED",
      // CLUSTER (only IDEMPOTENT_WRITE is allowed)
      "119, 119, Data owner,      IDEMPOTENT_WRITE,  CLUSTER, , ALLOWED",
      "119, 119, Data scientist,  IDEMPOTENT_WRITE,  CLUSTER, , ALLOWED",
      "119, 119, Data owner,      WRITE,  CLUSTER, , DENIED",
      "119, 119, Data scientist,  WRITE,  CLUSTER, , DENIED",
  })
  public void testAuthorizeAllow(int topicProjectId, int userProjectId, String projectRole, String operationName, String resourceName,
                                 String sharePermission, String expectedResult)
      throws UnknownHostException, ExecutionException {
    // Arrange
    Mockito.when(topicProjectCache.get(anyString())).thenReturn(topicProjectId);
    Mockito.when(userProjectCache.get(anyString())).thenReturn(new Pair<>(userProjectId, projectRole));
    Mockito.when(projectShareCache.get(any())).thenReturn(sharePermission);

    Action action = buildAction(operationName, resourceName, "test");

    // Act
    AuthorizationResult result = hopsAclAuthorizer.authorize("project__user", action);

    // Assert
    Assertions.assertEquals(AuthorizationResult.valueOf(expectedResult), result);
    if (ResourceType.TOPIC.equals(ResourceType.fromString(resourceName))) {
      Mockito.verify(topicProjectCache, Mockito.times(1)).get(anyString());
      Mockito.verify(userProjectCache, Mockito.times(1)).get(anyString());
      if (sharePermission != null) {
        Mockito.verify(projectShareCache, Mockito.times(1)).get(any());
      }
    }
  }

  @ParameterizedTest
  @CsvSource({
      // super user
      "user, principal_type:user, true",
      "user, 'principal_type:CN=user,O=io.strimzi', true",
      "user, principal_type:user;principal_type:user1;principal_type:user2, true",
      // super user with alternative name
      "user;user1;user2, principal_type:user, true",
      "user;user1;user2, 'principal_type:CN=user,O=io.strimzi;principal_type:CN=user3,O=io.strimzi', true",
      "user;user1;user2, principal_type:user;principal_type:user3;principal_type:user5, true",
      // not super user
      "user, '', false",
      "user, principal_type:user1, false",
      "user, 'principal_type:CN=user1,O=io.strimzi', false",
      "user3, principal_type:user;principal_type:user1;principal_type:user2, false",
      // not super user with alternative name
      "user;user1;user2, principal_type:user3, false",
      "user;user1;user2, 'principal_type:CN=user3,O=io.strimzi;principal_type:CN=user4,O=io.strimzi', false",
      "user;user1;user2, principal_type:user3;principal_type:user4;principal_type:user5, false"
  })
  public void testIsSuperUser(String principalName, String superUsersStr, boolean expectedResult) {
    // Arrange
    hopsAclAuthorizer.setSuperUsers(superUsersStr);

    List<String> subjectNames = Arrays.asList(principalName.split(Consts.SEMI_COLON));

    // Act
    boolean result = hopsAclAuthorizer.isSuperUser(subjectNames);

    // Assert
    Assertions.assertEquals(expectedResult, result);
  }

  private RequestContext buildRequestContext(KafkaPrincipal principal) throws UnknownHostException {
    return new RequestContext(
        null,
        null,
        InetAddress.getByName("10.0.2.15"),
        principal,
        null,
        null,
        null,
        false);
  }

  private Action buildAction(String operationName, String resourceName, String topicName) {
    return new Action(
        AclOperation.fromString(operationName),
        new ResourcePattern(ResourceType.fromString(resourceName), topicName, PatternType.LITERAL),
        0, false, false
    );
  }
}