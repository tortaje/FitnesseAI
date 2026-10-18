package com.fitnesseai.plugin;

import fitnesse.plugins.PluginFeatureFactoryBase;
import fitnesse.responders.ResponderFactory;

public class AiVerifyPluginFactory extends PluginFeatureFactoryBase {
  @Override
  public void registerResponders(ResponderFactory responderFactory) {
    responderFactory.addResponder("ai-verify", AiVerifyResponder.class);
    LOG.info("FitNesse AI Verify plugin loaded (responder: ai-verify)");
  }
}
