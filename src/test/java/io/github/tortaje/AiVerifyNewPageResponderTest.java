package io.github.tortaje;

import fitnesse.FitNesseContext;
import fitnesse.Responder;
import fitnesse.http.MockRequest;
import fitnesse.http.Response;
import fitnesse.http.SimpleResponse;
import fitnesse.testutil.FitNesseUtil;
import fitnesse.wiki.PathParser;
import fitnesse.wiki.WikiPage;
import fitnesse.wiki.WikiPageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiVerifyNewPageResponderTest {

  private FitNesseContext context;

  @AfterEach
  void tearDown() throws IOException {
    if (context != null) {
      FitNesseUtil.destroyTestContext(context);
      context = null;
    }
  }

  private static MockRequest newPageRequest() {
    MockRequest request = new MockRequest("FrontPage");
    request.setRequestLine("GET /FrontPage?new HTTP/1.1");
    request.addInput("responder", "new");
    return request;
  }

  @Test
  void pluginFactoryOverridesNewResponder() {
    context = FitNesseUtil.makeTestContext(new Properties());

    assertEquals(AiVerifyNewPageResponder.class, context.responderFactory.getResponderClass("new"));
  }

  @Test
  void newPageHtmlContainsAssetTags() throws Exception {
    context = FitNesseUtil.makeTestContext(new Properties());
    WikiPage root = context.getRootPage();
    WikiPageUtil.addPage(root, PathParser.parse("FrontPage"), "!CONTENTS");

    MockRequest request = newPageRequest();
    Responder responder = context.responderFactory.makeResponder(request);
    assertTrue(responder instanceof AiVerifyNewPageResponder);
    Response response = responder.makeResponse(context, request);

    assertTrue(response instanceof SimpleResponse);
    String html = new String(((SimpleResponse) response).getContentBytes(), StandardCharsets.UTF_8);
    assertTrue(html.contains("value=\"addChild\""), "must be the new-page form");
    assertTrue(html.contains("files/fitnesse/ai-verify/ai-verify.css"), "CSS tag injected");
    assertTrue(html.contains("files/fitnesse/ai-verify/ai-verify.js"), "JS tag injected");
    int linkIndex = html.indexOf("ai-verify.css");
    int bodyClose = html.lastIndexOf("</body>");
    assertTrue(linkIndex < bodyClose, "tags must be injected before </body>");
  }

  @Test
  void newPageFromTemplateHtmlContainsAssetTags() throws Exception {
    context = FitNesseUtil.makeTestContext(new Properties());
    WikiPage root = context.getRootPage();
    WikiPageUtil.addPage(root, PathParser.parse("FrontPage"), "!CONTENTS");
    WikiPageUtil.addPage(root, PathParser.parse("TemplateLibrary.StaticPage"), "static template body");

    MockRequest request = newPageRequest();
    request.addInput("pageTemplate", ".TemplateLibrary.StaticPage");
    Responder responder = context.responderFactory.makeResponder(request);
    Response response = responder.makeResponse(context, request);

    assertTrue(response instanceof SimpleResponse);
    String html = new String(((SimpleResponse) response).getContentBytes(), StandardCharsets.UTF_8);
    assertTrue(html.contains("value=\"addChild\""), "must be the new-page form");
    assertTrue(html.contains("static template body"), "template content pre-filled");
    assertTrue(html.contains("files/fitnesse/ai-verify/ai-verify.js"), "JS tag injected");
  }
}
