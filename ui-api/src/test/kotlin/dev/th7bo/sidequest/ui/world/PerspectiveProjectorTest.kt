package dev.th7bo.sidequest.ui.world

import dev.th7bo.sidequest.ui.geometry.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The projection, against geometry with a known answer.
 *
 * Written after a person reported that waypoints were nowhere near the thing they marked. The basis was built
 * from yaw and pitch with the handedness backwards, so the horizontal axis ran along the camera's left and the
 * vertical — derived from it — ran downwards: the picture was rotated 180° about the view axis, and every
 * waypoint appeared point-reflected through the centre of the screen.
 *
 * Nothing caught it. The headless tests all used a fake projector, which agrees with whatever the real one does,
 * and the one client test put its waypoint dead ahead at eye level — the single position a point-reflection
 * leaves alone — then asserted the overlay was *registered* rather than where it was drawn.
 *
 * So these are all cases where left and right, and up and down, give different answers.
 *
 * Minecraft's axes throughout: **+X east, +Z south, +Y up**. A camera facing south therefore has east on its
 * left hand.
 */
class PerspectiveProjectorTest {

    private val viewport = Size(640f, 360f)
    private val eye = WorldPosition(0.0, 64.0, 0.0)

    /** Facing south (+Z), level. The orientation Minecraft calls yaw 0. */
    private val facingSouth = ViewBasis(
        forward = WorldDirection(0.0, 0.0, 1.0),
        up = WorldDirection(0.0, 1.0, 0.0),
    )

    private fun projector(basis: ViewBasis = facingSouth, fov: Float = FOV) =
        PerspectiveProjector(eye, basis, fov, viewport)

    // -- the basis -----------------------------------------------------------

    /**
     * The regression, at the root.
     *
     * Facing south, the right hand points west — that is `-X`. The old code produced `+X`, which is east, and
     * east is the *left* hand. Everything else that went wrong followed from this one sign.
     */
    @Test
    fun `right is the camera's right hand and not its left`() {
        val right = facingSouth.right

        assertClose(-1.0, right.x, "facing south, right is west (-X), not east (+X)")
        assertClose(0.0, right.y)
        assertClose(0.0, right.z)
    }

    @Test
    fun `the basis stays orthonormal when it is handed something sloppy`() {
        // A forward that is not unit length and an up that is not quite perpendicular to it: what a camera
        // hands over after floating-point drift.
        val basis = ViewBasis(
            forward = WorldDirection(0.0, 0.0, 4.0),
            up = WorldDirection(0.0, 3.0, 0.0),
        )

        assertClose(1.0, length(basis.forward), "forward should be normalised")
        assertClose(1.0, length(basis.right), "right should be normalised")
        assertClose(0.0, basis.forward.dot(basis.right), "forward and right should be perpendicular")
    }

    // -- horizontal ----------------------------------------------------------

    @Test
    fun `a point dead ahead lands in the centre`() {
        val projection = projector().project(WorldPosition(0.0, 64.0, 20.0))

        assertFalse(projection.isBehind)
        assertClose(viewport.width / 2.0, projection.screenPosition.x.toDouble())
        assertClose(viewport.height / 2.0, projection.screenPosition.y.toDouble())
        assertClose(20.0, projection.distance)
    }

    /**
     * Facing south, west is on the right. So a block to the west draws right of centre.
     *
     * This is the case the bug inverted, and the one a waypoint placed straight ahead can never show.
     */
    @Test
    fun `a point to the west draws right of centre when facing south`() {
        val projection = projector().project(WorldPosition(-10.0, 64.0, 20.0))

        assertTrue(
            projection.screenPosition.x > viewport.width / 2f,
            "west is on the right hand of somebody facing south, got x=${projection.screenPosition.x}",
        )
        assertClose(viewport.height / 2.0, projection.screenPosition.y.toDouble(), "it should not move vertically")
    }

    @Test
    fun `a point to the east draws left of centre when facing south`() {
        val projection = projector().project(WorldPosition(10.0, 64.0, 20.0))

        assertTrue(
            projection.screenPosition.x < viewport.width / 2f,
            "east is on the left hand of somebody facing south, got x=${projection.screenPosition.x}",
        )
    }

    /** Turning to face east must move a fixed target the opposite way across the screen. */
    @Test
    fun `turning the camera moves a target the other way`() {
        val target = WorldPosition(10.0, 64.0, 20.0)

        val south = projector().project(target).screenPosition.x
        val facingEast = ViewBasis(WorldDirection(1.0, 0.0, 0.0), WorldDirection(0.0, 1.0, 0.0))
        val east = projector(facingEast).project(target).screenPosition.x

        assertTrue(south < viewport.width / 2f, "left of centre while facing south")
        assertTrue(east > viewport.width / 2f, "and right of centre once turned to face it, got x=$east")
    }

    // -- vertical ------------------------------------------------------------

    /**
     * Up in the world is up on the screen.
     *
     * Screen Y grows downwards, so something above the camera has to come out *below* half the height. The bug
     * had this backwards too, which is why it read as more than a mirror.
     */
    @Test
    fun `a point above the camera draws above the centre`() {
        val projection = projector().project(WorldPosition(0.0, 74.0, 20.0))

        assertTrue(
            projection.screenPosition.y < viewport.height / 2f,
            "higher in the world is higher on screen, got y=${projection.screenPosition.y}",
        )
        assertClose(viewport.width / 2.0, projection.screenPosition.x.toDouble(), "it should not move horizontally")
    }

    @Test
    fun `a point below the camera draws below the centre`() {
        val projection = projector().project(WorldPosition(0.0, 54.0, 20.0))

        assertTrue(
            projection.screenPosition.y > viewport.height / 2f,
            "got y=${projection.screenPosition.y}",
        )
    }

    /** Looking down, a block on the ground ahead rises towards the centre of the screen. */
    @Test
    fun `pitching the camera down brings the ground up the screen`() {
        val ground = WorldPosition(0.0, 44.0, 20.0)

        val level = projector().project(ground).screenPosition.y
        // Forty-five degrees down: forward and up both tilt, and the basis re-derives right on its own.
        val tilted = ViewBasis(
            forward = WorldDirection(0.0, -1.0, 1.0),
            up = WorldDirection(0.0, 1.0, 1.0),
        )
        val down = projector(tilted).project(ground).screenPosition.y

        assertTrue(level > viewport.height / 2f, "below the centre while looking level")
        assertTrue(down < level, "and higher up the screen once the camera looks down, $down vs $level")
    }

    // -- the frustum ---------------------------------------------------------

    /**
     * A target at exactly half the vertical field of view lands on the top edge.
     *
     * The check that the field of view is read as vertical and in degrees, which is what Minecraft's own
     * projection matrix does with it — it multiplies by π/180 and passes it to JOML as `fovy`.
     */
    @Test
    fun `the vertical field of view reaches exactly the top edge`() {
        // tan(35°) * 20 blocks away is the height that sits on the edge of a 70° vertical frustum.
        val height = Math.tan(Math.toRadians(FOV / 2.0)) * 20.0
        val projection = projector().project(WorldPosition(0.0, 64.0 + height, 20.0))

        assertClose(0.0, projection.screenPosition.y.toDouble(), "should sit on the top edge")
        assertTrue(projection.isOnScreen(viewport))
    }

    /** The horizontal extent follows from the aspect, so a wide viewport sees further to the sides. */
    @Test
    fun `a wider viewport fits more in horizontally`() {
        val square = PerspectiveProjector(eye, facingSouth, FOV, Size(360f, 360f))
        // At 20 blocks a square viewport reaches tan(35°) * 20 = 14.0 blocks to the side, and a 16:9 one
        // reaches 16/9 of that — 24.9. Twenty blocks west falls between the two.
        val target = WorldPosition(-20.0, 64.0, 20.0)

        val wide = projector().project(target)
        val narrow = square.project(target)

        assertTrue(wide.isOnScreen(viewport), "a 16:9 viewport should still contain it")
        assertFalse(narrow.isOnScreen(Size(360f, 360f)), "a square one should not")
    }

    // -- behind --------------------------------------------------------------

    @Test
    fun `a point behind the camera is flagged rather than drawn`() {
        val projection = projector().project(WorldPosition(0.0, 64.0, -20.0))

        assertTrue(projection.isBehind)
        assertFalse(projection.isOnScreen(viewport), "behind is never on screen, whatever the coordinate says")
        assertClose(20.0, projection.distance, "the distance is still real")
    }

    /** Standing inside the marker. Dividing by this depth is what the near plane exists to prevent. */
    @Test
    fun `a point at the camera does not produce a NaN`() {
        val projection = projector().project(eye)

        assertTrue(projection.isBehind)
        assertFalse(projection.screenPosition.x.isNaN())
        assertFalse(projection.screenPosition.y.isNaN())
    }

    /**
     * Distance is the real one, not the depth along the view axis.
     *
     * The fade reads this, so a marker off to one side must not appear nearer than it is.
     */
    @Test
    fun `distance is measured to the camera and not along the view axis`() {
        val projection = projector().project(WorldPosition(30.0, 64.0, 40.0))

        assertClose(50.0, projection.distance, "3-4-5")
    }

    private fun length(direction: WorldDirection) = Math.sqrt(direction.dot(direction))

    private fun assertClose(expected: Double, actual: Double, message: String = "") {
        assertTrue(
            abs(expected - actual) < TOLERANCE,
            if (message.isEmpty()) "expected $expected, got $actual" else "$message (expected $expected, got $actual)",
        )
        // Keeps the failure output showing both numbers when the tolerance check is the one that fired.
        assertEquals(expected, actual, TOLERANCE)
    }

    private companion object {
        /** Minecraft's default vertical field of view. */
        const val FOV = 70f
        const val TOLERANCE = 1e-4
    }
}
