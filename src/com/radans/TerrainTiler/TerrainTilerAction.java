/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.radans.TerrainTiler;

import com.jme3.math.Vector3f;
import com.jme3.terrain.geomipmap.TerrainQuad;

/**
 * Allows the parent code to get notified of changes to the tiles
 * so it can make changes or add/remove things like objects.
 * @author radan
 */
public interface TerrainTilerAction {
    
    /**
     * Called when a Tile has been attached to the TilerNode
     * @param center - Vector3f world location
     * @param tile - the TerrainQuad attached
     */
    public void tileAttached(Vector3f center, TerrainQuad tile);
    
    /**
     * Called when a Tile has been Detached from the TilerNode
     * @param center - Vector3f world location
     * @param tile - the TerrainQuad attached
     */
    public void tileDetached(Vector3f center, TerrainQuad tile);
    
}
