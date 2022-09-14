package de.westnordost.streetcomplete.data.osm.mapdata

import de.westnordost.streetcomplete.data.download.tiles.asBoundingBoxOfEnclosingTiles
import de.westnordost.streetcomplete.data.download.tiles.enclosingTilePos
import de.westnordost.streetcomplete.data.download.tiles.enclosingTilesRect
import de.westnordost.streetcomplete.data.osm.geometry.ElementPointGeometry
import de.westnordost.streetcomplete.testutils.mock
import de.westnordost.streetcomplete.testutils.node
import de.westnordost.streetcomplete.testutils.on
import de.westnordost.streetcomplete.testutils.rel
import de.westnordost.streetcomplete.testutils.way
import de.westnordost.streetcomplete.util.ktx.containsExactlyInAnyOrder
import de.westnordost.streetcomplete.util.math.enclosingBoundingBox
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.verify

internal class MapDataCacheTest {

    @Test fun `update puts way`() {
        val way = way(1)
        val cache = MapDataCache(16, 4, 10) { emptyList<Element>() to emptyList() }
        cache.update(addedOrUpdatedElements = listOf(way))
        val elementDB: ElementDao = mock()
        on(elementDB.get(ElementType.WAY, 1L)).thenThrow(IllegalStateException())
        assertEquals(way, cache.getElement(ElementType.WAY, 1L) { type, id -> elementDB.get(type, id) })
    }

    @Test fun `getting non-node fetches from DB if not cached, and then caches`() {
        val way = way(2L)
        val cache = MapDataCache(16, 4, 10) { emptyList<Element>() to emptyList() }
        val elementDB: ElementDao = mock()
        on(elementDB.get(ElementType.WAY, 2L)).thenReturn(way).thenThrow(IllegalStateException()) // throw on second call
        // get way 2 and verify the fetch function is called, but only once (put it twice and throw the second time!)
        assertEquals(way, cache.getElement(ElementType.WAY, 2L) { type, id -> elementDB.get(type, id) })
        verify(elementDB).get(ElementType.WAY, 2L)
        // getting a second time does not fetch again
        assertEquals(way, cache.getElement(ElementType.WAY, 2L) { type, id -> elementDB.get(type, id) })
    }

    @Test fun `getting node always fetches if node not in spatialCache`() {
        val node = node(1)
        val cache = MapDataCache(16, 4, 10) { emptyList<Element>() to emptyList() }
        val elementDB: ElementDao = mock()
        on(elementDB.get(ElementType.NODE, 1L)).thenReturn(node).thenReturn(null)
        assertEquals(node, cache.getElement(ElementType.NODE, 1L) { type, id -> elementDB.get(type, id) })
        verify(elementDB).get(ElementType.NODE, 1L)
        // getting a second time fetches again
        assertNull(cache.getElement(ElementType.NODE, 1L) { type, id -> elementDB.get(type, id) })
    }

    @Test fun `update only puts nodes if tile is cached`() {
        val node = node(1, LatLon(0.0, 0.0))
        val cache = MapDataCache(16, 4, 10) { emptyList<Element>() to emptyList() }
        val nodeTile = node.position.enclosingTilePos(16)
        assertEquals(0, cache.getMapDataWithGeometry(nodeTile.asBoundingBox(16)).size)
        // now we have nodeTile cached
        cache.update(addedOrUpdatedElements = listOf(node))
        assertTrue(cache.getMapDataWithGeometry(nodeTile.asBoundingBox(16)).nodes.containsExactlyInAnyOrder(listOf(node)))
        assertEquals(node, cache.getElement(ElementType.NODE, 1L) { _,_ -> null })

        val otherNode = node(2, LatLon(0.0, 1.0))
        val otherNodeTile = otherNode.position.enclosingTilePos(16)
        cache.update(addedOrUpdatedElements = listOf(otherNode))
        assertNull(cache.getElement(ElementType.NODE, 2L) { _,_ -> null })
        assertTrue(cache.getMapDataWithGeometry(otherNodeTile.asBoundingBox(16)).nodes.isEmpty())

        val movedNode = node(1, LatLon(1.0, 0.0))
        val movedNodeTile = movedNode.position.enclosingTilePos(16)
        cache.update(addedOrUpdatedElements = listOf(movedNode))
        assertNull(cache.getElement(ElementType.NODE, 1L) { _,_ -> null })
        assertTrue(cache.getMapDataWithGeometry(movedNodeTile.asBoundingBox(16)).nodes.isEmpty())
        assertTrue(cache.getMapDataWithGeometry(nodeTile.asBoundingBox(16)).nodes.isEmpty())
    }

    @Test fun `update removes elements`() {
        val node = node(1, LatLon(0.0, 0.0))
        val way = way(2)
        val cache = MapDataCache(16, 4, 10) { listOf(node) to emptyList() }
        cache.getMapDataWithGeometry(node.position.enclosingTilePos(16).asBoundingBox(16))
        cache.update(addedOrUpdatedElements = listOf(way))
        assertTrue(
            cache.getElements(listOf(ElementKey(ElementType.NODE, 1L), ElementKey(ElementType.WAY, 2L))) { emptyList() }
                .containsExactlyInAnyOrder(listOf(node, way))
        )
        cache.update(deletedKeys = listOf(ElementKey(ElementType.NODE, 1L), ElementKey(ElementType.WAY, 2L)))
        assertNull(cache.getElement(ElementType.NODE, 1L) { _,_ -> null })
        assertNull(cache.getElement(ElementType.WAY, 2L) { _,_ -> null })
    }

    @Test fun `getWaysForNode caches from db`() {
        val way1 = way(1, nodes = listOf(1L, 2L))
        val way2 = way(2, nodes = listOf(3L, 1L))
        val way3 = way(3, nodes = listOf(3L, 2L))
        val cache = MapDataCache(16, 4, 10) { emptyList<Element>() to emptyList() }
        cache.update(addedOrUpdatedElements = listOf(way1, way2, way3))
        val wayDB: WayDao = mock()
        on(wayDB.getAllForNode(1L)).thenReturn(listOf(way1, way2)).thenThrow(IllegalStateException())
        // fetches from cache if we didn't put the node
        assertTrue(cache.getWaysForNode(1L) { wayDB.getAllForNode(it) }.containsExactlyInAnyOrder(listOf(way1, way2)))
        verify(wayDB).getAllForNode(1L)
        // now we have it cached
        assertTrue(cache.getWaysForNode(1L) { wayDB.getAllForNode(it) }.containsExactlyInAnyOrder(listOf(way1, way2)))
    }

    @Test fun `getWaysForNode gets filled inside bbox`() {
        val node1 = node(1, LatLon(0.0, 0.0))
        val node2 = node(2, LatLon(0.0001, 0.0001))
        val node3 = node(3, LatLon(0.0002, 0.0002))
        val nodesRect = listOf(node1.position, node2.position, node3.position).enclosingBoundingBox().enclosingTilesRect(16)
        assertTrue(nodesRect.size <= 4) // fits in cache
        val way1 = way(1, nodes = listOf(1L, 2L))
        val way2 = way(2, nodes = listOf(3L, 1L))
        val way3 = way(3, nodes = listOf(3L, 2L))
        val cache = MapDataCache(16, 4, 10) { emptyList<Element>() to emptyList() }
        cache.update(addedOrUpdatedElements = listOf(node1, node2, node3, way1, way2, way3), bbox = nodesRect.asBoundingBox(16))

        assertTrue(cache.getWaysForNode(1L) { emptyList() }.containsExactlyInAnyOrder(listOf(way1, way2)))
        assertTrue(cache.getWaysForNode(2L) { emptyList() }.containsExactlyInAnyOrder(listOf(way1, way3)))
        assertTrue(cache.getWaysForNode(2L) { emptyList() }.containsExactlyInAnyOrder(listOf(way1, way3)))
    }

    @Test fun `update affects wayIdsByNodeIdCache`() {
        val node1 = node(1, LatLon(0.0, 0.0))
        val node2 = node(2, LatLon(0.0001, 0.0001))
        val node3 = node(3, LatLon(0.0002, 0.0002))
        val nodesRect = listOf(node1.position, node2.position, node3.position).enclosingBoundingBox().enclosingTilesRect(16)
        assertTrue(nodesRect.size <= 4) // fits in cache
        val way1 = way(1, nodes = listOf(1L, 2L))
        val way2 = way(2, nodes = listOf(3L, 1L))
        val way3 = way(3, nodes = listOf(3L, 2L))
        val cache = MapDataCache(16, 4, 10) { emptyList<Element>() to emptyList() }
        cache.update(addedOrUpdatedElements = listOf(node1, node2, node3, way1, way2, way3), bbox = nodesRect.asBoundingBox(16))
        assertTrue(cache.getWaysForNode(1L) { emptyList() }.containsExactlyInAnyOrder(listOf(way1, way2)))

        val way3updated = way(3, nodes = listOf(3L, 2L, 1L))
        cache.update(addedOrUpdatedElements = listOf(way3updated))
        assertTrue(cache.getWaysForNode(1L) { emptyList() }.containsExactlyInAnyOrder(listOf(way1, way2, way3updated)))
    }

    @Test fun `update affects relationIdsByElementKeyCache`() {
        val node1 = node(1, LatLon(0.0, 0.0))
        val node2 = node(2, LatLon(0.0001, 0.0001))
        val nodesRect = listOf(node1.position, node2.position).enclosingBoundingBox().enclosingTilesRect(16)
        assertTrue(nodesRect.size <= 4) // fits in cache
        val way1 = way(1, nodes = listOf(1L, 2L))
        val rel1 = rel(1, members = listOf(RelationMember(ElementType.NODE, 1L, "")))
        val rel2 = rel(2, members = listOf(RelationMember(ElementType.WAY, 1L, "")))
        val cache = MapDataCache(16, 4, 10) { emptyList<Element>() to emptyList() }
        cache.update(addedOrUpdatedElements = listOf(node1, node2, way1, rel1, rel2), bbox = nodesRect.asBoundingBox(16))
        assertTrue(cache.getRelationsForNode(1L) { emptyList() }.containsExactlyInAnyOrder(listOf(rel1)))
        assertTrue(cache.getRelationsForWay(1L) { emptyList() }.containsExactlyInAnyOrder(listOf(rel2)))

        val rel2updated = rel(2, members = listOf(RelationMember(ElementType.NODE, 1L, "")))
        cache.update(addedOrUpdatedElements = listOf(rel2updated))
        assertTrue(cache.getRelationsForNode(1L) { emptyList() }.containsExactlyInAnyOrder(listOf(rel1, rel2updated)))
        assertTrue(cache.getRelationsForWay(1L) { emptyList() }.isEmpty())
    }

    @Test fun `trim removes everything not referenced by spatialCache`() {
        val node1 = node(1, LatLon(0.0, 0.0))
        val node2 = node(2, LatLon(0.0001, 0.0001))
        val node3 = node(3, LatLon(0.0002, 0.0002))
        val nodes = listOf(node1, node2, node3)
        val outsideNode = node(4, LatLon(1.0, 1.0))
        val nodesRect = listOf(node1.position, node2.position, node3.position).enclosingBoundingBox().enclosingTilesRect(16)
        assertTrue(nodesRect.size <= 4) // fits in cache

        val way1 = way(1, nodes = listOf(1L, 2L))
        val way2 = way(2, nodes = listOf(3L, 1L))
        val way3 = way(3, nodes = listOf(3L, 2L, 4L))
        val ways = listOf(way1, way2, way3)
        val outsideWay = way(4, nodes = listOf(4L, 5L))
        val rel1 = rel(1, members = listOf(RelationMember(ElementType.NODE, 1L, ""), RelationMember(ElementType.WAY, 5L, "")))
        val outsideRel = rel(2, members = listOf(RelationMember(ElementType.NODE, 4L, ""), RelationMember(ElementType.WAY, 5L, "")))
        val cache = MapDataCache(16, 4, 10) { emptyList<Element>() to emptyList() }
        cache.update(addedOrUpdatedElements = nodes + ways + listOf(outsideNode, outsideWay, rel1, outsideRel), bbox = nodesRect.asBoundingBox(16))

        val expectedMapData = MutableMapDataWithGeometry(nodes + ways + rel1, emptyList())
        // node geometries get created when getting the data
        nodes.forEach { expectedMapData.putGeometry(it.type, it.id, ElementPointGeometry(it.position)) }
        // way and relation geometries are also put, though null
        ways.forEach { expectedMapData.putGeometry(it.type, it.id, null) }
        expectedMapData.putGeometry(rel1.type, rel1.id, null)
        expectedMapData.boundingBox = nodesRect.asBoundingBox(16)
        assertEquals(expectedMapData, cache.getMapDataWithGeometry(nodesRect.asBoundingBox(16)))

        assertEquals(null, cache.getNode(4L)) // node not in spatialCache
        assertEquals(outsideWay, cache.getElement(ElementType.WAY, 4L) { _,_ -> null })
        assertEquals(outsideRel, cache.getElement(ElementType.RELATION, 2L) { _,_ -> null })
        cache.trim(4)
        // outside way and relation removed
        assertEquals(null, cache.getElement(ElementType.WAY, 4L) { _,_ -> null })
        assertEquals(null, cache.getElement(ElementType.RELATION, 2L) { _,_ -> null })
        assertEquals(rel1, cache.getElement(ElementType.RELATION, 1L) { _,_ -> null })
    }

    @Test fun `add relation with members already in spatial cache`() {
        val node = node(1, LatLon(0.0, 0.0))
        val nodeRectBbox = node.position.enclosingBoundingBox(0.1).asBoundingBoxOfEnclosingTiles(16)

        val way = way(1, nodes = listOf(1L, 2L))
        val cache = MapDataCache(16, 4, 10) { emptyList<Element>() to emptyList() }
        cache.update(addedOrUpdatedElements = listOf(way, node), bbox = nodeRectBbox)

        val rel1 = rel(1, members = listOf(RelationMember(ElementType.NODE, 1, "")))
        val rel2 = rel(2, members = listOf(RelationMember(ElementType.WAY, 1, "")))

        cache.update(addedOrUpdatedElements = listOf(rel1, rel2))

        assertEquals(listOf(rel1), cache.getRelationsForNode(1) { throw(IllegalStateException()) })
        assertEquals(listOf(rel2), cache.getRelationsForWay(1) { throw(IllegalStateException()) })
    }
}
