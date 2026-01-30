#!/usr/bin/env node

/**
 * Extracts the version from build.sbt and makes it available to Docusaurus.
 * This script reads the version from build.sbt and writes it to a JSON file
 * that can be imported by the Docusaurus config.
 */

const fs = require('fs');
const path = require('path');

// Path to build.sbt (one level up from website/)
const buildSbtPath = path.join(__dirname, '..', 'build.sbt');
// Output path for version JSON
const versionJsonPath = path.join(__dirname, 'version.json');

try {
  // Read build.sbt
  const buildSbtContent = fs.readFileSync(buildSbtPath, 'utf8');

  // Extract version using regex
  // Looking for: ThisBuild / version := "0.1.0-SNAPSHOT"
  const versionMatch = buildSbtContent.match(/ThisBuild\s*\/\s*version\s*:=\s*"([^"]+)"/);

  if (!versionMatch) {
    console.error('Error: Could not find version in build.sbt');
    process.exit(1);
  }

  const version = versionMatch[1];
  console.log(`Extracted version: ${version}`);

  // Write version to JSON file
  const versionData = {
    version: version,
    extractedAt: new Date().toISOString()
  };

  fs.writeFileSync(versionJsonPath, JSON.stringify(versionData, null, 2));
  console.log(`Version written to ${versionJsonPath}`);

} catch (error) {
  console.error('Error extracting version:', error.message);
  process.exit(1);
}
