import React from 'react';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';

/**
 * Component that displays the cedar4s version from build.sbt.
 * Can be used inline in MDX files for displaying the version in text.
 *
 * Usage in MDX:
 *   import Version from '@site/src/components/Version';
 *
 *   The current version is <Version />.
 *
 * Note: For code blocks, prefer using the {{VERSION}} placeholder instead,
 * which is replaced at build time by the remark-version-plugin.
 */
export default function Version(): JSX.Element {
  const {siteConfig} = useDocusaurusContext();
  const version = siteConfig.customFields?.cedar4sVersion as string || '0.1.0-SNAPSHOT';
  return <code>{version}</code>;
}
