import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

// Load version from build.sbt
let cedar4sVersion = '0.1.0-SNAPSHOT'; // fallback
try {
  const versionData = require('./version.json');
  cedar4sVersion = versionData.version;
} catch (error) {
  console.warn('Warning: Could not load version.json, using fallback version');
}

const config: Config = {
  title: 'cedar4s',
  tagline: 'Type-safe Cedar authorization for Scala',
  favicon: 'img/logo.svg',

  // Custom fields to make version available in components
  customFields: {
    cedar4sVersion: cedar4sVersion,
  },

  url: 'https://devnico.github.io',
  baseUrl: '/cedar4s/',

  organizationName: 'devnico',
  projectName: 'cedar4s',

  onBrokenLinks: 'throw',

  markdown: {
    mermaid: true,
  },

  themes: ['@docusaurus/theme-mermaid'],

  plugins: ['@orama/plugin-docusaurus-v3'],

  // Future configuration options are available in Docusaurus 3.9+
  future: {
    v4: {
      removeLegacyPostBuildHeadAttribute: true,
    },
  },

  presets: [
    [
      'classic',
      {
        docs: {
          sidebarPath: './sidebars.ts',
          editUrl: 'https://github.com/devnico/cedar4s/edit/main/website/docs/',
          path: './docs',
          // Inject version into MDX scope
          remarkPlugins: [
            [
              require('./plugins/remark-version-plugin.js'),
              { version: cedar4sVersion }
            ]
          ],
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    navbar: {
      title: 'cedar4s',
      logo: {
        alt: 'cedar4s Logo',
        src: 'img/logo.svg',
      },
      items: [
        {
          type: 'doc',
          docId: 'overview/intro',
          position: 'left',
          label: 'Documentation',
        },
        {
          href: 'https://github.com/devnico/cedar4s',
          label: 'GitHub',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Docs',
          items: [
            {
              label: 'Get Started',
              to: '/docs/overview/intro',
            },
            {
              label: 'Quickstart',
              to: '/docs/overview/quickstart',
            },
          ],
        },
        {
          title: 'More',
          items: [
            {
              label: 'GitHub',
              href: 'https://github.com/devnico/cedar4s',
            },
            {
              label: 'Cedar Policy Language',
              href: 'https://docs.cedarpolicy.com/',
            },
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} Nicolas Schlecker. Built with Docusaurus.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['java', 'scala', 'bash', 'json', 'cedar', 'cedarschema'],
    },
    mermaid: {
      options: {
        // ELK layout engine for better diagram rendering (Docusaurus 3.9+)
        // Requires: npm install @mermaid-js/layout-elk
        // layout: 'elk',
      },
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
