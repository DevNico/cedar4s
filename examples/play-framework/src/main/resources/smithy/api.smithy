$version: "2"

namespace example.playframework.api

use smithy.api#http
use smithy.api#httpError
use smithy.api#httpLabel
use smithy.api#httpQuery
use smithy.api#readonly
use smithy.api#required
use example.playframework.authz#DocumentCapabilitiesMixin

service PlayFrameworkApi {
  version: "2025-01-01"
  operations: [IssueToken, GetDocument, CreateDocument, ListDocuments]
}

@http(method: "POST", uri: "/auth/token", code: 200)
operation IssueToken {
  input: IssueTokenInput
  output: IssueTokenOutput
}

structure IssueTokenInput {
  @required
  userId: String
}

structure IssueTokenOutput {
  @required
  token: String
}

@readonly
@http(method: "GET", uri: "/documents/{documentId}", code: 200)
operation GetDocument {
  input: GetDocumentInput
  output: Document
  errors: [UnauthorizedError, ForbiddenError, NotFoundError]
}

structure GetDocumentInput {
  @required
  @httpLabel
  documentId: String
}

@http(method: "POST", uri: "/documents", code: 201)
operation CreateDocument {
  input: CreateDocumentInput
  output: Document
  errors: [UnauthorizedError, ForbiddenError]
}

structure CreateDocumentInput {
  @required
  orgId: String
  @required
  title: String
  @required
  visibility: String
}

@readonly
@http(method: "GET", uri: "/documents", code: 200)
operation ListDocuments {
  input: ListDocumentsInput
  output: ListDocumentsOutput
  errors: [UnauthorizedError]
}

structure ListDocumentsInput {
  @required
  @httpQuery("orgId")
  orgId: String
}

structure ListDocumentsOutput {
  @required
  documents: DocumentList
}

list DocumentList {
  member: Document
}

structure Document with [DocumentCapabilitiesMixin] {
  @required
  id: String
  @required
  orgId: String
  @required
  ownerId: String
  @required
  title: String
  @required
  visibility: String
}

@httpError(401)
@error("client")
structure UnauthorizedError {
  @required
  message: String
}

@httpError(403)
@error("client")
structure ForbiddenError {
  @required
  message: String
}

@httpError(404)
@error("client")
structure NotFoundError {
  @required
  message: String
}
