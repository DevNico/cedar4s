/**
 * Copyright (c) Cedar Contributors
 * SPDX-License-Identifier: Apache-2.0
 * 
 * Prism language definitions for Cedar policy language and Cedar schema language.
 * Based on the official highlightjs-cedar grammar from cedar-policy/highlightjs-cedar
 */

import siteConfig from '@generated/docusaurus.config';

export default function prismIncludeLanguages(PrismObject) {
  const {
    themeConfig: { prism },
  } = siteConfig;
  const { additionalLanguages } = prism;

  // Cedar Policy Language
  PrismObject.languages.cedar = {
    'comment': /\/\/.*/,
    
    'string': {
      pattern: /"(?:\\.|[^\\"\r\n])*"/,
      greedy: true,
    },

    'policy-keyword': {
      pattern: /\b(?:permit|forbid|when|unless)\b/,
      alias: 'keyword',
    },

    'keyword': /\b(?:if|then|else|in|like|has|is)\b/,

    'boolean': /\b(?:true|false)\b/,

    'variable': {
      pattern: /\b(?:principal|action|resource|context)\b/,
      alias: 'builtin',
    },

    'template-variable': {
      pattern: /\?(?:resource|principal)\b/,
      alias: 'constant',
    },

    'function': {
      pattern: /\b(?:ip|decimal|datetime|duration)\s*(?=\()/,
      alias: 'builtin',
    },

    'method': {
      pattern: /\.(?:contains|containsAll|containsAny|isEmpty|getTag|hasTag|lessThan|lessThanOrEqual|greaterThan|greaterThanOrEqual|isIpv4|isIpv6|isLoopback|isMulticast|isInRange|offset|durationSince|toDate|toTime|toMilliseconds|toSeconds|toMinutes|toHours|toDays)\s*(?=\()/,
      lookbehind: true,
    },

    'entity-type': {
      pattern: /\b(?:[_a-zA-Z][_a-zA-Z0-9]*::)+[_a-zA-Z][_a-zA-Z0-9]*(?=::)/,
      alias: 'class-name',
    },

    'entity': {
      pattern: /\b(?:[_a-zA-Z][_a-zA-Z0-9]*::)*[_a-zA-Z][_a-zA-Z0-9]*::"[^"]+"/,
      inside: {
        'class-name': /^[^:]+/,
        'string': /"[^"]+"/,
        'punctuation': /::/,
      },
    },

    'operator': /&&|\|\||==|!=|>=|<=|[><=+\-*]/,

    'number': /\b(?:0|-?[1-9](?:_?[0-9])*)\b/,

    'punctuation': /[{}[\](),;.]/,
  };

  // Cedar Schema Language
  PrismObject.languages.cedarschema = {
    'comment': /\/\/.*/,

    'string': {
      pattern: /"(?:\\.|[^\\"\r\n])*"/,
      greedy: true,
    },

    'keyword': /\b(?:namespace|type|entity|action|in|appliesTo|tags)\b/,

    'property': {
      pattern: /\b[_a-zA-Z][_a-zA-Z0-9]*(?=\??:(?!:))/,
    },

    'type': {
      pattern: /\b(?:String|Long|Bool|Set)\b/,
      alias: 'class-name',
    },

    'entity-type': {
      pattern: /\b[_a-zA-Z][_a-zA-Z0-9]*(?:::[_a-zA-Z][_a-zA-Z0-9]*)*/,
      alias: 'class-name',
    },

    'operator': /=/,

    'punctuation': /[{}[\](),;:?]/,
  };

  // Also register as 'cedar-policy' and 'cedar-schema' for convenience
  PrismObject.languages['cedar-policy'] = PrismObject.languages.cedar;
  PrismObject.languages['cedar-schema'] = PrismObject.languages.cedarschema;

  // Load any additional languages from Docusaurus config
  globalThis.Prism = PrismObject;
  additionalLanguages.forEach((lang) => {
    if (lang === 'cedar' || lang === 'cedarschema') {
      // Already defined above
      return;
    }
    require(`prismjs/components/prism-${lang}`);
  });
  delete globalThis.Prism;
}
