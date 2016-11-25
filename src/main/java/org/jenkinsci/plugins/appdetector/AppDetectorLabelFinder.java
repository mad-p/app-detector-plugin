package org.jenkinsci.plugins.appdetector;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.LabelFinder;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.model.labels.LabelAtom;
import hudson.slaves.ComputerListener;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.appdetector.task.AppDetectionTask;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.LogManager;
import java.util.logging.Logger;

@Extension
public class AppDetectorLabelFinder extends LabelFinder {

  private final Map<Node, Set<LabelAtom>> cashedLabels
      = new ConcurrentHashMap<Node, Set<LabelAtom>>();

  @Override
  public Collection<LabelAtom> findLabels(Node node) {
    Computer computer = node.toComputer();
    if (computer == null || node.getChannel() == null) {
      return Collections.emptyList();
    }

    Set<LabelAtom> applications = cashedLabels.get(node);
    if (applications == null || applications.isEmpty()) {
      return Collections.emptyList();
    }

    return applications;
  }

  @Extension
  public static class AppDetectorComputerListener extends ComputerListener {

    @Override
    public void onOnline(Computer computer, TaskListener taskListener) {
      Set<LabelAtom> applications = detectInstalledApplications(computer);
      if (!applications.isEmpty()) {
        finder().cashedLabels.put(computer.getNode(), applications);
      } else {
        finder().cashedLabels.remove(computer.getNode());
      }
    }

    @Override
    public void onConfigurationChange() {
      AppDetectorLabelFinder finder = finder();

      Set<Node> cachedNodes = new HashSet<Node>(finder.cashedLabels.keySet());

      Jenkins jenkins = Jenkins.getInstance();

      if (jenkins == null) {
        return;
      }

      List<Node> realNodes = jenkins.getNodes();
      for (Node node: cachedNodes) {
        if (!realNodes.contains(node)) {
          finder.cashedLabels.remove(node);
        }
      }
    }

    private AppDetectorLabelFinder finder() {
      return LabelFinder.all().get(AppDetectorLabelFinder.class);
    }

    private Set<LabelAtom> detectInstalledApplications(Computer computer) {
      Logger logger = LogManager.getLogManager().getLogger("hudson.WebAppMain");

      Set<LabelAtom> applications = new HashSet<LabelAtom>();

      Jenkins hudsonInstance = Jenkins.getInstance();
      if (hudsonInstance == null) {
        logger.warning(Messages.CANNOT_GET_HUDSON_INSTANCE());
        return applications;
      }

      AppDetectorBuildWrapper.DescriptorImpl descriptor =
          hudsonInstance.getDescriptorByType(AppDetectorBuildWrapper.DescriptorImpl.class);

      Boolean isUnix = computer.isUnix();
      //This computer seems offline. So, skip detection.
      if (isUnix == null) {
        return applications;
      }

      for (AppDetectionSetting setting: descriptor.getDetectionSettings()) {
        try {
          if (isUnix) {
            Set<String> serializedApplications = new HashSet<String>();
            AppDetectionTask task = new AppDetectionTask(setting);
            serializedApplications.addAll(computer.getChannel().call(task));
            for (String applicationString: serializedApplications) {
              applications.add(AppLabelAtom.deserialize(applicationString));
            }
          }
        } catch (Exception e) {
          logger.warning(
              Messages.DETECTING_SOFTOWARE_INSTLLATION_FAILED(setting.getAppName(), computer.getDisplayName()));
          e.printStackTrace();
        }
      }
      return applications;
    }
  }
}
