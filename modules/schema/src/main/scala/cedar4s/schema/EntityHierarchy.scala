package cedar4s.schema

/** Analyzed entity hierarchy from a Cedar schema.
  *
  * Provides pure graph utilities for understanding entity relationships. This class has NO domain-specific assumptions -
  * it works with any Cedar schema regardless of authorization pattern.
  *
  * For domain-specific ownership analysis (e.g., tenant identification), use the cedar-scala-codegen module which
  * provides configurable strategies.
  */
final case class EntityHierarchy(
    entities: Map[String, EntityDecl],
    parentMap: Map[String, Set[String]], // entity -> direct parents
    childMap: Map[String, Set[String]] // entity -> direct children
) {

  // ==========================================================================
  // Direct Relationships
  // ==========================================================================

  /** Get direct parents of an entity */
  def parentsOf(entityName: String): Set[String] =
    parentMap.getOrElse(entityName, Set.empty)

  /** Get direct children of an entity */
  def childrenOf(entityName: String): Set[String] =
    childMap.getOrElse(entityName, Set.empty)

  // ==========================================================================
  // Transitive Relationships
  // ==========================================================================

  /** Get all ancestors (transitive parents) of an entity */
  def ancestorsOf(entityName: String): Set[String] = {
    def go(current: String, visited: Set[String]): Set[String] = {
      if (visited.contains(current)) visited
      else {
        val parents = parentsOf(current)
        parents.foldLeft(visited + current) { (acc, parent) =>
          go(parent, acc)
        }
      }
    }
    go(entityName, Set.empty) - entityName
  }

  /** Get all descendants (transitive children) of an entity */
  def descendantsOf(entityName: String): Set[String] = {
    def go(current: String, visited: Set[String]): Set[String] = {
      if (visited.contains(current)) visited
      else {
        val children = childrenOf(current)
        children.foldLeft(visited + current) { (acc, child) =>
          go(child, acc)
        }
      }
    }
    go(entityName, Set.empty) - entityName
  }

  /** Check if entity A is an ancestor of entity B */
  def isAncestorOf(ancestor: String, descendant: String): Boolean =
    ancestorsOf(descendant).contains(ancestor)

  /** Check if entity A is a descendant of entity B */
  def isDescendantOf(descendant: String, ancestor: String): Boolean =
    isAncestorOf(ancestor, descendant)

  // ==========================================================================
  // Graph Properties
  // ==========================================================================

  /** Get root entities (entities with no parents) */
  def roots: Set[String] =
    entities.keys.filter(e => parentsOf(e).isEmpty).toSet

  /** Get leaf entities (entities with no children) */
  def leaves: Set[String] =
    entities.keys.filter(e => childrenOf(e).isEmpty).toSet

  /** Get the minimum depth of an entity from any root. Root entities have depth 0. Returns -1 if entity is not found or
    * not connected to any root.
    */
  def depthOf(entityName: String): Int = {
    if (!entities.contains(entityName)) -1
    else if (roots.contains(entityName)) 0
    else {
      val parents = parentsOf(entityName)
      if (parents.isEmpty) 0 // Also a root (no parents)
      else {
        val parentDepths = parents.map(depthOf).filter(_ >= 0)
        if (parentDepths.isEmpty) -1 // Not connected to any root
        else parentDepths.min + 1
      }
    }
  }

  /** Get the maximum depth in the hierarchy */
  def maxDepth: Int = {
    val depths = entities.keys.map(depthOf).filter(_ >= 0).toList
    if (depths.isEmpty) 0 else depths.max
  }

  /** Get all entities at a specific depth */
  def entitiesAtDepth(depth: Int): Set[String] =
    entities.keys.filter(e => depthOf(e) == depth).toSet

  // ==========================================================================
  // Path Finding
  // ==========================================================================

  /** Find all paths from one entity to another (following parent edges). Returns empty list if no path exists.
    */
  def pathsTo(from: String, to: String): List[List[String]] = {
    if (from == to) List(List(from))
    else {
      val parents = parentsOf(from)
      parents.toList.flatMap { parent =>
        pathsTo(parent, to).map(from :: _)
      }
    }
  }

  /** Find the shortest path from one entity to another. Returns None if no path exists.
    */
  def shortestPathTo(from: String, to: String): Option[List[String]] = {
    val paths = pathsTo(from, to)
    if (paths.isEmpty) None
    else Some(paths.minBy(_.length))
  }

  /** Check if there is any path from one entity to another.
    */
  def hasPathTo(from: String, to: String): Boolean =
    from == to || ancestorsOf(from).contains(to)

  // ==========================================================================
  // Annotation Queries
  // ==========================================================================

  /** Get all entities that have a specific annotation.
    *
    * Example: `hierarchy.entitiesWithAnnotation("principal")` returns all entities marked with `@principal`.
    */
  def entitiesWithAnnotation(annotationName: String): Set[String] =
    entities.collect {
      case (name, decl) if decl.hasAnnotation(annotationName) => name
    }.toSet

  /** Get entities grouped by their annotations. Returns a map from annotation name to set of entity names.
    */
  def entitiesByAnnotation: Map[String, Set[String]] = {
    val annotatedEntities = for {
      (name, decl) <- entities
      annot <- decl.annotations
    } yield (annot.name, name)

    annotatedEntities.groupBy(_._1).map { case (annotName, pairs) =>
      annotName -> pairs.map(_._2).toSet
    }
  }

  // ==========================================================================
  // Entity Queries
  // ==========================================================================

  /** Get entity declaration by name */
  def entity(name: String): Option[EntityDecl] = entities.get(name)

  /** Check if an entity exists in the hierarchy */
  def contains(name: String): Boolean = entities.contains(name)

  /** Get all entity names */
  def entityNames: Set[String] = entities.keySet

  /** Get total number of entities */
  def size: Int = entities.size

  // ==========================================================================
  // Subgraph Operations
  // ==========================================================================

  /** Get a subgraph containing only the specified entity and all its ancestors.
    */
  def ancestorSubgraph(entityName: String): EntityHierarchy = {
    val relevantEntities = ancestorsOf(entityName) + entityName
    filterEntities(relevantEntities)
  }

  /** Get a subgraph containing only the specified entity and all its descendants.
    */
  def descendantSubgraph(entityName: String): EntityHierarchy = {
    val relevantEntities = descendantsOf(entityName) + entityName
    filterEntities(relevantEntities)
  }

  /** Filter the hierarchy to only include specified entities.
    */
  def filterEntities(keep: Set[String]): EntityHierarchy = {
    val filteredEntities = entities.filter { case (name, _) => keep.contains(name) }
    val filteredParentMap = parentMap.collect {
      case (name, parents) if keep.contains(name) => name -> parents.filter(keep.contains)
    }
    val filteredChildMap = childMap.collect {
      case (name, children) if keep.contains(name) => name -> children.filter(keep.contains)
    }
    EntityHierarchy(filteredEntities, filteredParentMap, filteredChildMap)
  }
}

object EntityHierarchy {

  /** Build an EntityHierarchy from a CedarSchema */
  def build(schema: CedarSchema): EntityHierarchy = {
    val entities = schema.allEntities.map(e => e.name -> e).toMap

    val parentMap = entities.map { case (name, decl) =>
      name -> decl.memberOf.map(_.simple).toSet
    }

    val childMap = parentMap.toList
      .flatMap { case (child, parents) => parents.map(parent => parent -> child) }
      .groupBy(_._1)
      .map { case (parent, pairs) => parent -> pairs.map(_._2).toSet }

    EntityHierarchy(entities, parentMap, childMap)
  }

  /** Create an empty hierarchy */
  val empty: EntityHierarchy = EntityHierarchy(Map.empty, Map.empty, Map.empty)
}
