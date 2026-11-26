package pro.sketchware.bridge;

/** Panelin yeri: robotun altı, sığmazsa üstü, o da olmazsa yanı. Hiçbiri yoksa robot yukarı kayar. */
final class BridgePlacement {
    private BridgePlacement() {
    }

    /** @return {panel x, panel y, robotun yeni y konumu} */
    static int[] place(int robotX, int robotY, int size, int width, int height,
                       int screenWidth, int screenHeight) {
        int centered = clamp(robotX + size / 2 - width / 2, screenWidth - width);
        if (robotY + size + height <= screenHeight) {
            return new int[]{centered, robotY + size, robotY};
        }
        if (robotY - height >= 0) {
            return new int[]{centered, robotY - height, robotY};
        }
        // Hiçbir yana sığmıyorsa daha geniş olan taraf seçilir.
        int rightSpace = screenWidth - robotX - size;
        if (Math.max(rightSpace, robotX) >= width) {
            int x = rightSpace >= robotX ? robotX + size : robotX - width;
            return new int[]{x, clamp(robotY + size / 2 - height / 2, screenHeight - height), robotY};
        }
        int raised = clamp(robotY, screenHeight - size - height);
        return new int[]{centered, raised + size, raised};
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(value, max));
    }
}
