/**
 * Remark plugin to replace version placeholders in documentation.
 * This plugin replaces {{VERSION}} with the actual version from build.sbt.
 *
 * Usage in MDX files:
 *   libraryDependencies += "io.github.devnico" %% "cedar4s-core" % "{{VERSION}}"
 */

const {visit} = require('unist-util-visit');

module.exports = function remarkVersionPlugin(options) {
  const version = options?.version || '0.1.0-SNAPSHOT';

  return (tree) => {
    // Replace {{VERSION}} in code blocks and text nodes
    visit(tree, ['code', 'inlineCode', 'text'], (node) => {
      if (node.value && typeof node.value === 'string') {
        node.value = node.value.replace(/\{\{VERSION\}\}/g, version);
      }
    });
  };
};
