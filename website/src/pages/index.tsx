import clsx from 'clsx';
import Link from '@docusaurus/Link';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import Layout from '@theme/Layout';

import styles from './index.module.css';

function HomepageHeader() {
  const {siteConfig} = useDocusaurusContext();
  return (
    <header className={clsx('hero hero--primary', styles.heroBanner)}>
      <div className="container">
        <div className={styles.earlyStage}>
          <b>Early Stage</b> — cedar4s is under active development. APIs may change between releases.{' '}
          <Link to="https://github.com/DevNico/cedar4s/issues">We'd love your feedback!</Link>
        </div>
        <div className={styles.heroLogo}>
          <img src="img/logo.svg" alt="cedar4s logo" className={styles.logoImage} />
          <h1 className={styles.heroTitle}>{siteConfig.title}</h1>
        </div>
        <p className={styles.heroSubtitle}>{siteConfig.tagline}</p>
        <div className={styles.buttons}>
          <Link
            className="button button--secondary button--lg"
            to="/docs/overview/intro">
            Get Started
          </Link>
        </div>
      </div>
    </header>
  );
}

function HomepageFeatures() {
  return (
    <section className={styles.features}>
      <div className="container">
        <div className={styles.doubleGrid}>
          <div>
            <h2>1. Define Authorization Schema</h2>
            <p>
              Start by defining your authorization model in <b>Cedar</b>, a policy language
              created by AWS for fine-grained access control.
            </p>
            <p>
              Cedar separates <b>policy logic</b> from application code, making authorization
              rules auditable and easy to understand.
            </p>
            <p>
              Define entities (users, resources), actions, and their relationships.
              cedar4s generates type-safe Scala code from this schema.
            </p>
          </div>
          <div className={styles.codeExample}>
            <div className={styles.codeTitle}>schema.cedarschema</div>
            <pre className={styles.codeBlock}><code>{`namespace DocShare;

entity User {}

entity Folder {}

entity Document in [Folder] {}

action "Document::Read" appliesTo {
  principal: [User],
  resource: Document
};

action "Document::Write" appliesTo {
  principal: [User],
  resource: Document
};`}</code></pre>
          </div>
        </div>

        <div className={styles.doubleGrid}>
          <div>
            <h2>2. Implement Entity Fetchers</h2>
            <p>
              <b>cedar4s</b> generates Scala traits and case classes from your Cedar schema.
              Implement <code>EntityFetcher</code> to load entities from your database.
            </p>
            <p>
              The <code>fetchBatch</code> method enables efficient bulk loading,
              reducing N+1 queries when authorizing multiple resources.
            </p>
            <p>
              Built-in caching with Caffeine minimizes database hits for
              frequently accessed entities.
            </p>
          </div>
          <div className={styles.codeExample}>
            <div className={styles.codeTitle}>DocumentFetcher.scala</div>
            <pre className={styles.codeBlock}><code>{`class DocumentFetcher(db: Database)
    extends EntityFetcher[IO, Entities.Document, String] {

  def fetch(id: String): IO[Option[Entities.Document]] =
    db.findDocument(id).map(_.map { doc =>
      Entities.Document(id = doc.id, folderId = doc.folderId)
    })

  override def fetchBatch(ids: Set[String]) =
    db.findDocuments(ids).map(_.map(d => d.id -> toCedar(d)).toMap)
}`}</code></pre>
          </div>
        </div>

        <div className={styles.doubleGrid}>
          <div>
            <h2>3. Authorize with Type Safety</h2>
            <p>
              Use the generated request types to perform authorization checks.
              No string-based action names or resource IDs that can drift from your schema.
            </p>
            <p>
              <b>Batch operations</b> like <code>filterAllowed</code> efficiently
              authorize multiple resources in a single pass.
            </p>
            <p>
              Works with any effect type: <code>Future</code>, cats-effect <code>IO</code>,
              <code>ZIO</code>, or your own.
            </p>
          </div>
          <div className={styles.codeExample}>
            <div className={styles.codeTitle}>Authorization.scala</div>
            <pre className={styles.codeBlock}><code>{`import myapp.cedar.MyApp

val runtime = CedarRuntime[IO](engine, store, CedarRuntime.resolverFrom(buildPrincipal))

given session: CedarSession[IO] = runtime.session(currentUser)

// Type-safe authorization check
MyApp.Document.Read(folderId, documentId).require

// Check without throwing
val canRead: IO[Boolean] = MyApp.Document.Read(folderId, documentId).isAllowed

// Batch filter - only returns allowed documents
val allowed: IO[Seq[Document]] = session.filterAllowed(documents) { doc =>
  MyApp.Document.Read(doc.folderId, doc.id)
}`}</code></pre>
          </div>
        </div>
      </div>
    </section>
  );
}

export default function Home(): JSX.Element {
  const {siteConfig} = useDocusaurusContext();
  return (
    <Layout
      title="Type-safe Cedar authorization for Scala"
      description="Type-safe Cedar authorization for Scala">
      <HomepageHeader />
      <main>
        <HomepageFeatures />
      </main>
    </Layout>
  );
}
