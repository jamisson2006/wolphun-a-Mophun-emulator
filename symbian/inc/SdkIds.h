/* SdkIds.h - internal ids for the Mophun SDK APIs.
 * Shared between Sdk.cpp (the name->id table) and SdkApi.cpp (the dispatch switch).
 * SDK aliases (vNewPtr / vMalloc / vNewPtrDbg, for instance) share one id.
 */
#ifndef WOLPHUN_SDKIDS_H
#define WOLPHUN_SDKIDS_H

enum {
    A_UNKNOWN = -1,
    A_alloc = 0, A_allocClear, A_free, A_memFree,
    A_getTickCount, A_getTime, A_getTimeDate, A_getRandom, A_setRandom,
    A_getCaps, A_sysCtl, A_terminate,
    A_setStackSize, A_createTask, A_disposeTask, A_taskAlive, A_thisTask,
    A_send, A_receiveAny, A_receive,
    A_abs, A_htons, A_htonl,
    A_strLenU, A_strCpyU, A_strToU, A_streamReady, A_strCmp,
    A_checkCert, A_checkIMEI, A_checkNetwork, A_getVMGPInfo, A_uid,
    A_frameTickCount, A_yieldToSystem, A_waitVBL,
    A_testKey, A_scanKeys, A_beep,
    A_clearScreen, A_flipScreen, A_setForeColor, A_setBackColor,
    A_setPaletteEntry, A_getPaletteEntry, A_setPalette, A_setTransferMode,
    A_setClipWindow, A_fillRect, A_drawLine, A_plot, A_getPixel,
    A_updateSpriteMap, A_getButtonData,
    A_spriteInit, A_spriteClear, A_spriteSet, A_updateSprite,
    A_drawObject, A_drawTile, A_updateSpriteRotate,
    A_spriteBoxCollision, A_spriteCollision,
    A_mapInit, A_mapHeaderUpdate, A_mapDispose, A_updateMap, A_mapSetXY,
    A_mapGetAttribute, A_mapGetTile, A_mapSetTile, A_mapSetAttribute,
    A_selectFont, A_setActiveFont, A_textOut, A_textOutU, A_print,
    A_textExtent, A_textExtentU, A_charExtent, A_msgBox, A_msgBoxU,
    A_strLen, A_strCpy, A_itoa, A_utoa, A_strCat, A_atoi,
    A_decompress, A_decompHdr,
    A_streamOpen, A_streamRead, A_streamWrite, A_streamSeek, A_streamClose,
    A_playResource,
    A_soundInit, A_soundGetHandle, A_soundCtrl, A_soundCtrlEx,
    A_soundDispose, A_soundDisposeHandle, A_soundUpload,
    A_musicInit, A_musicLoad, A_musicNoop,
    A_copyRect, A_sprintf, A_sprintfVa, A_findRGBIndex,
    A_mul16, A_div16, A_sinF16, A_cosF16, A_limit,
    A_getSupportedRenderStates, A_setDisplayWindow,
    /* ---- 3D: the VMGP3D pipeline (Renderer3D module) ---- */
    A_init3D, A_setViewport, A_setRenderState, A_setZBuffer,
    A_setTexture, A_createTexture, A_setActiveTexture, A_setFunctionalTexture,
    A_deleteTexture, A_deleteAllTextures, A_freeTexture,
    A_renderPrimitive, A_renderPrimitiveIndexed,
    A_drawPolygon, A_drawBillboard, A_drawFlatPolygon, A_getInteger,
    A_setMatrixMode, A_matrixIdentity, A_matrixSetCurrent, A_matrixGetCurrent,
    A_matrixMultiply, A_matrixSetProjection, A_matrixSetLight,
    A_matrixTranslate, A_matrixScale,
    A_matrixRotateX, A_matrixRotateY, A_matrixRotateZ, A_matrixRotateVector,
    A_matrixPerspective, A_matrixOrtho, A_matrixLookAt,
    A_matrixInvert, A_matrixTranspose, A_matrixMultiply3x3,
    A_vectorAdd, A_vectorSub, A_vectorMul, A_vectorNormalize,
    A_crossProduct, A_dotProduct,
    A_vectorTransformV3, A_vectorTransformV4,
    A_vectorProjectV3, A_vectorProjectV4,
    A_boxInViewFrustum, A_createPlaneFromPoly,
    A_collisionPointBox, A_collisionBoxBox, A_collisionVectorPoly, A_collisionVectorPlane,
    A_setLight, A_setMaterial, A_setMaterial2, A_setAmbientLight,
    A_setCameraPos, A_resetLights, A_lightPoint, A_setFogColor,
    A_getZBufferValue, A_vectorArrayAdd, A_vectorArrayDelta,
    A_pow, A_mul, A_div, A_sqrt, A_sin, A_cos, A_tan,
    A_COUNT
};

#endif /* WOLPHUN_SDKIDS_H */
