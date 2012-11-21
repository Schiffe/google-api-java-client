/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.api.client.googleapis.services;

import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.googleapis.subscriptions.MemorySubscriptionStore;
import com.google.api.client.googleapis.subscriptions.SubscriptionHeaders;
import com.google.api.client.googleapis.testing.services.MockGoogleClient;
import com.google.api.client.googleapis.testing.services.MockGoogleClientRequest;
import com.google.api.client.googleapis.testing.subscriptions.MockNotificationCallback;
import com.google.api.client.http.HttpMethods;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.Json;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.json.jackson.JacksonFactory;
import com.google.api.client.testing.http.HttpTesting;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.client.util.Key;

import junit.framework.TestCase;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Tests {@link AbstractGoogleClient}.
 *
 * @author Yaniv Inbar
 */
public class AbstractGoogleClientTest extends TestCase {

  private static final JsonFactory JSON_FACTORY = new JacksonFactory();
  private static final JsonObjectParser JSON_OBJECT_PARSER = new JsonObjectParser(JSON_FACTORY);
  private static final HttpTransport TRANSPORT = new NetHttpTransport();

  public static class MyC4lient extends AbstractGoogleClient {

    public MyC4lient(HttpTransport transport, String rootUrl, String servicePath) {
      super(transport, null, rootUrl, servicePath, null);
    }

    public static class Builder extends AbstractGoogleClient.Builder {

      protected Builder(HttpTransport transport) {
        super(transport, HttpTesting.SIMPLE_URL, "test/", null, null);
      }

      @Override
      public AbstractGoogleClient build() {
        return new MyC4lient(getTransport(), getRootUrl(), getServicePath());
      }

    }
  }

  static private class TestRemoteRequestInitializer implements GoogleClientRequestInitializer {

    boolean isCalled;

    TestRemoteRequestInitializer() {
    }

    public void initialize(AbstractGoogleClientRequest<?> request) {
      isCalled = true;
    }
  }

  public void testGoogleClientBuilder() {
    String rootUrl = "http://www.testgoogleapis.com/test/";
    String servicePath = "path/v1/";
    GoogleClientRequestInitializer jsonHttpRequestInitializer = new TestRemoteRequestInitializer();
    String applicationName = "Test Application";

    AbstractGoogleClient.Builder setApplicationName = new MockGoogleClient.Builder(
        TRANSPORT, rootUrl, servicePath, JSON_OBJECT_PARSER, null).setApplicationName(
        applicationName).setGoogleClientRequestInitializer(jsonHttpRequestInitializer);
    AbstractGoogleClient client = setApplicationName.build();

    assertEquals(rootUrl + servicePath, client.getBaseUrl());
    assertEquals(rootUrl, client.getRootUrl());
    assertEquals(servicePath, client.getServicePath());
    assertEquals(applicationName, client.getApplicationName());
    assertEquals(jsonHttpRequestInitializer, client.getGoogleClientRequestInitializer());
  }

  public void testBaseServerAndBasePathBuilder() {
    AbstractGoogleClient client = new MockGoogleClient.Builder(
        TRANSPORT, "http://www.testgoogleapis.com/test/", "path/v1/", JSON_OBJECT_PARSER,
        null).setApplicationName("Test Application")
        .setRootUrl("http://www.googleapis.com/test/").setServicePath("path/v2/").build();

    assertEquals("http://www.googleapis.com/test/path/v2/", client.getBaseUrl());
  }

  public void testInitialize() throws Exception {
    TestRemoteRequestInitializer remoteRequestInitializer = new TestRemoteRequestInitializer();
    AbstractGoogleClient client = new MockGoogleClient.Builder(
        TRANSPORT, "http://www.test.com/", "", JSON_OBJECT_PARSER, null).setApplicationName(
        "Test Application").setGoogleClientRequestInitializer(remoteRequestInitializer).build();
    client.initialize(null);
    assertTrue(remoteRequestInitializer.isCalled);
  }

  /** Tests the normal flow execution. */
  public void testSubscribe() throws Exception {
    MemorySubscriptionStore store = new MemorySubscriptionStore();
    MockHttpTransport transport = new MockHttpTransport() {
        @Override
      public LowLevelHttpRequest buildRequest(final String method, String url) {
        return new MockLowLevelHttpRequest(url) {
            @Override
          public LowLevelHttpResponse execute() {
            MockLowLevelHttpResponse response = new MockLowLevelHttpResponse();
            assertEquals(HttpMethods.POST, method);
            // Headers are always stored internally as all lower case
            assertEquals("something",
                getHeaders().get(SubscriptionHeaders.SUBSCRIBE.toLowerCase()).get(0));
            String clientToken =
                getHeaders().get(SubscriptionHeaders.CLIENT_TOKEN.toLowerCase()).get(0);

            response.addHeader(SubscriptionHeaders.SUBSCRIPTION_ID, "12345");
            response.addHeader(SubscriptionHeaders.CLIENT_TOKEN, clientToken);
            response.addHeader(SubscriptionHeaders.TOPIC_ID, "topicID");
            response.addHeader(SubscriptionHeaders.TOPIC_URI, "http://topic.uri/");
            return response;
          }
        };
      }
    };
    AbstractGoogleClient client = new MockGoogleClient.Builder(
        transport, HttpTesting.SIMPLE_URL, "", JSON_OBJECT_PARSER, null).setApplicationName(
        "Test Application").setSubscriptionStore(store).build();
    MockGoogleClientRequest<String> rq =
        new MockGoogleClientRequest<String>(client, "GET", "", null, String.class);
    rq.subscribeUnparsed("something", new MockNotificationCallback()).executeUnparsed();
    assertEquals(1, store.listSubscriptions().size());
    assertEquals("12345", rq.getLastSubscription().getSubscriptionID());
  }

  private static final String TEST_RESUMABLE_REQUEST_URL =
      "http://www.test.com/request/url?uploadType=resumable";
  private static final String TEST_UPLOAD_URL = "http://www.test.com/media/upload/location";
  private static final String TEST_CONTENT_TYPE = "image/jpeg";

  private static class MediaTransport extends MockHttpTransport {

    int bytesUploaded;
    int contentLength = MediaHttpUploader.DEFAULT_CHUNK_SIZE;

    protected MediaTransport() {
    }

    @Override
    public LowLevelHttpRequest buildRequest(String name, String url) {
      if (name.equals("POST")) {
        assertEquals(TEST_RESUMABLE_REQUEST_URL, url);

        return new MockLowLevelHttpRequest() {
            @Override
          public LowLevelHttpResponse execute() {
            // Assert that the required headers are set.
            assertEquals(Integer.toString(contentLength),
                getHeaders().get("x-upload-content-length").get(0));
            assertEquals(TEST_CONTENT_TYPE, getHeaders().get("x-upload-content-type").get(0));
            // This is the initiation call. Return 200 with the upload URI.
            MockLowLevelHttpResponse response = new MockLowLevelHttpResponse();
            response.setStatusCode(200);
            response.addHeader("Location", TEST_UPLOAD_URL);
            return response;
          }
        };
      }
      assertEquals(TEST_UPLOAD_URL, url);

      return new MockLowLevelHttpRequest() {
          @Override
        public LowLevelHttpResponse execute() {
          MockLowLevelHttpResponse response = new MockLowLevelHttpResponse();

          String bytesRange =
              bytesUploaded + "-" + (bytesUploaded + MediaHttpUploader.DEFAULT_CHUNK_SIZE - 1);
          String expectedContentRange = "bytes " + bytesRange + "/" + contentLength;
          assertEquals(expectedContentRange, getHeaders().get("Content-Range").get(0));
          bytesUploaded += MediaHttpUploader.DEFAULT_CHUNK_SIZE;

          if (bytesUploaded == contentLength) {
            // Return 200 since the upload is complete.
            response.setStatusCode(200);
            response.setContent("{\"foo\":\"somevalue\"}");
            response.setContentType(Json.MEDIA_TYPE);
          } else {
            // Return 308 and the range since the upload is incomplete.
            response.setStatusCode(308);
            response.addHeader("Range", bytesRange);
          }
          return response;
        }
      };
    }
  }

  public static class A {
    @Key
    String foo;
  }

  public void testMediaUpload() throws Exception {
    MediaTransport transport = new MediaTransport();
    AbstractGoogleClient client = new MockGoogleClient.Builder(
        transport, TEST_RESUMABLE_REQUEST_URL, "", JSON_OBJECT_PARSER, null).setApplicationName(
        "Test Application").build();
    InputStream is = new ByteArrayInputStream(new byte[MediaHttpUploader.DEFAULT_CHUNK_SIZE]);
    InputStreamContent mediaContent = new InputStreamContent(TEST_CONTENT_TYPE, is);
    mediaContent.setLength(MediaHttpUploader.DEFAULT_CHUNK_SIZE);
    MockGoogleClientRequest<A> rq =
        new MockGoogleClientRequest<A>(client, "POST", "", null, A.class);
    rq.initializeMediaUpload(mediaContent);
    A result = rq.execute();
    assertEquals("somevalue", result.foo);
  }

  public void testMediaUpload_disableGZip() throws Exception {
    MediaTransport transport = new MediaTransport();
    AbstractGoogleClient client = new MockGoogleClient.Builder(
        transport, TEST_RESUMABLE_REQUEST_URL, "", JSON_OBJECT_PARSER, null).setApplicationName(
        "Test Application").build();
    InputStream is = new ByteArrayInputStream(new byte[MediaHttpUploader.DEFAULT_CHUNK_SIZE]);
    InputStreamContent mediaContent = new InputStreamContent(TEST_CONTENT_TYPE, is);
    mediaContent.setLength(MediaHttpUploader.DEFAULT_CHUNK_SIZE);
    MockGoogleClientRequest<A> rq =
        new MockGoogleClientRequest<A>(client, "POST", "", null, A.class);
    rq.initializeMediaUpload(mediaContent);
    rq.setDisableGZipContent(true);
    try {
      rq.execute();
      fail("expected " + IllegalArgumentException.class);
    } catch (IllegalArgumentException e) {
      // expected
    }
  }
}
