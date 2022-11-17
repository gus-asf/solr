package org.apache.solr.jersey.example;

import org.apache.solr.core.CoreContainer;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.process.internal.RequestScoped;
import org.glassfish.jersey.server.ResourceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;

public class AdminResourceConfig extends ResourceConfig {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public AdminResourceConfig() {
    register(new AbstractBinder() {
      @Override
      public void configure() {
        log.info("configure");
        bindFactory(CoreContainerFactory.class).to(CoreContainer.class)
            .in(RequestScoped.class);
      }
    });
    register(LukeResource.class);
  }

}
