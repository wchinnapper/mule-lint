package org.nuisto

import groovy.json.JsonBuilder
import groovy.util.logging.Slf4j

@Slf4j(category = 'org.nuisto.msa')
class Runner {
  int runWithModel(OptionsModel optionsModel) {
    File path = new File(optionsModel.sourceDirectory)

    if (!path.exists()) {
      log.error 'Provided directory, {}, does not exist.', path.absolutePath
      System.exit(ErrorCodes.ProvidedDirectoryDoesNotExist)
    }

    File rulesPath = new File(optionsModel.rules)

    if (!rulesPath.exists()) {
      log.error 'Provided rules, {}, does not exist.', rulesPath.absolutePath
      System.exit(ErrorCodes.ProvidedDirectoryDoesNotExist)
    }

    log.info 'Using source directory: {}', path.absolutePath
    log.info 'Using rules: {}', new File(optionsModel.rules).absolutePath

    RulesLoader loader = new RulesLoader()
    List<Expectation> expectations = loader.load(optionsModel)

    def txtFiles = new FileNameFinder().getFileNames(path.absolutePath, '**/*.xml' /* includes */, 'pom.xml **/*.pdf' /* excludes */)

    log.info 'Found {} files', txtFiles.size()

    Map<String, List<Infraction> > expectationFindings = [:]


    txtFiles.each {
      processFile(it, expectations)

      //TODO There might be times where the user wants to explicitly see "0" infractions for a file.
      //A user might like to see this so they know for sure the file was looked at.
      //It might also be able to be brought in as a calculation (if ratio of 0 no infractions to found is > than X, then fail build)
      //Could also use this as a trending chart
      if (expectations.findings.flatten().size() > 0)
        expectationFindings.put(it, expectations.findings.flatten())

      expectations.each { it.reset() }
    }

    def json = new JsonBuilder()
    def root = json {
      version '0.0.1'

      findings expectationFindings.collect { String fileName, List<Infraction> infractions ->
        [
          'file'    : fileName,
          'messages': infractions
        ]
      }
    }

    if (optionsModel.resultsFile != null) {
      File file = new File(optionsModel.resultsFile)

      println file.absolutePath

      file.write(json.toPrettyString())
    }
    else {
      def msg = 'Output file was not specified, this is probably use less with it. But looking for suggestions.'
      log.error(msg)
      System.err.println msg
    }

    return 0
  }

  def processFile(String file, List<Expectation> expectations) {

    log.debug('Checking {}', file)

    Map<String, String> foundNamespaces = new Hashtable<String, String>()
    def parser = new PeakNamespacesXmlParser(foundNamespaces)
    def root = parser.parse(file)

    NodeChecker nodeChecker = new NodeChecker(foundNamespaces)

    if (nodeChecker.isRoot(root)) {
      log.info 'Processing {}', file

      processEachNode(root, expectations, nodeChecker)
    }
    else {
      log.debug 'Skipping file as not a mule file: {}', file
    }
  }

  def processEachNode(Node node, List<Expectation> expectations, NodeChecker nodeChecker) {

    expectations.each {
      it.handleNode(node, nodeChecker)
    }

    node.children().each { it ->
      // There are times when the Node could be a text node and in that case, we get a String
      if (it instanceof Node) processEachNode((Node)it, expectations, nodeChecker)
    }
  }
}
