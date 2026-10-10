/* Sdk.cpp - ported from src/wolphun/vm/Sdk.java (see Sdk.h).
 *
 * The explanatory comments from the original -- the WHY behind each decision, found by
 * testing real games -- were kept: they are where this emulator's value lives.
 */
#include "Sdk.h"
#include "Pip2.h"
#include "Screen.h"
#include "SdkIds.h"

/* ================= IntMap (open addressing) ================= */
IntMap::IntMap() : keys(0), vals(0), state(0), cap(0), count(0) { grow(); }
IntMap::~IntMap(){ delete[] keys; delete[] vals; delete[] state; }

void IntMap::clear(){
    for (int i=0;i<cap;i++) state[i]=0;
    count=0;
}
void IntMap::grow(){
    int ncap = cap ? cap*2 : 64;
    int*  nk = new int[ncap];
    int*  nv = new int[ncap];
    u8*   ns = new u8[ncap];
    for (int i=0;i<ncap;i++){ ns[i]=0; nk[i]=0; nv[i]=0; }
    for (int i=0;i<cap;i++){
        if (state[i]!=1) continue;
        u32 hpos = ((u32)keys[i] * 2654435761u) & (u32)(ncap-1);
        while (ns[hpos]==1) hpos = (hpos+1) & (u32)(ncap-1);
        ns[hpos]=1; nk[hpos]=keys[i]; nv[hpos]=vals[i];
    }
    delete[] keys; delete[] vals; delete[] state;
    keys=nk; vals=nv; state=ns; cap=ncap;
}
void IntMap::put(int key,int val){
    if ((count+1)*4 >= cap*3) grow();
    u32 h = ((u32)key * 2654435761u) & (u32)(cap-1);
    int firstDel = -1;
    while (state[h]!=0){
        if (state[h]==1 && keys[h]==key){ vals[h]=val; return; }
        if (state[h]==2 && firstDel<0) firstDel=(int)h;
        h = (h+1) & (u32)(cap-1);
    }
    int slot = (firstDel>=0) ? firstDel : (int)h;
    state[slot]=1; keys[slot]=key; vals[slot]=val; count++;
}
bool IntMap::get(int key,int* val) const {
    if (!cap) return false;
    u32 h = ((u32)key * 2654435761u) & (u32)(cap-1);
    int guard = cap;
    while (state[h]!=0 && guard-- > 0){
        if (state[h]==1 && keys[h]==key){ if(val)*val=vals[h]; return true; }
        h = (h+1) & (u32)(cap-1);
    }
    return false;
}
bool IntMap::remove(int key,int* oldVal){
    if (!cap) return false;
    u32 h = ((u32)key * 2654435761u) & (u32)(cap-1);
    int guard = cap;
    while (state[h]!=0 && guard-- > 0){
        if (state[h]==1 && keys[h]==key){
            if(oldVal)*oldVal=vals[h];
            state[h]=2; count--; return true;
        }
        h = (h+1) & (u32)(cap-1);
    }
    return false;
}
bool IntMap::next(int* it,int* key,int* val) const {
    for (int i=*it; i<cap; i++){
        if (state[i]==1){ if(key)*key=keys[i]; if(val)*val=vals[i]; *it=i+1; return true; }
    }
    return false;
}

/* ================= the API table (name -> id) ================= */
/* Aliases share one id. resolve() does a linear search, but it is called ONCE per pool
   entry -- Pip2 caches the id -- and never on the hot path. */

struct ApiEntry { const char* name; int id; };
static const ApiEntry kApi[] = {
    /* memory */
    {"vNewPtr",A_alloc},{"vMalloc",A_alloc},{"vNewPtrDbg",A_alloc},
    {"vNewPtrClear",A_allocClear},{"vAllocClear",A_allocClear},{"vCallocPtr",A_allocClear},
    {"vDisposePtr",A_free},{"vFreePtr",A_free},
    {"vMemFree",A_memFree},{"vMaxFreeBlock",A_memFree},{"vMemAvail",A_memFree},
    /* system */
    {"vGetTickCount",A_getTickCount},{"vGetTime",A_getTime},
    {"vGetTimeDate",A_getTimeDate},{"vGetTimeDateUTC",A_getTimeDate},
    {"vGetRandom",A_getRandom},{"vSetRandom",A_setRandom},
    {"vGetCaps",A_getCaps},{"vSysCtl",A_sysCtl},{"vTerminateVMGP",A_terminate},
    {"vSetStackSize",A_setStackSize},{"vCreateTask",A_createTask},
    {"vDisposeTask",A_disposeTask},{"vTaskAlive",A_taskAlive},{"vThisTask",A_thisTask},
    {"vSend",A_send},{"vReceiveAny",A_receiveAny},{"vReceive",A_receive},
    {"vAbs",A_abs},{"htons",A_htons},{"htonl",A_htonl},
    {"vStrLenU",A_strLenU},{"vStrCpyU",A_strCpyU},{"vStrToU",A_strToU},
    {"vStreamReady",A_streamReady},{"vStrCmp",A_strCmp},
    {"vCheckDataCert",A_checkCert},{"vCheckDataCertFile",A_checkCert},
    {"vCheckIMEI",A_checkIMEI},{"vCheckNetwork",A_checkNetwork},
    {"vGetVMGPInfo",A_getVMGPInfo},{"vUID",A_uid},
    {"vFrameTickCount",A_frameTickCount},{"vYieldToSystem",A_yieldToSystem},
    {"vWaitVBL",A_waitVBL},
    {"vTestKey",A_testKey},{"vScanKeys",A_scanKeys},{"vBeep",A_beep},
    /* graphics */
    {"vClearScreen",A_clearScreen},{"vFlipScreen",A_flipScreen},
    {"vSetForeColor",A_setForeColor},{"vSetBackColor",A_setBackColor},
    {"vSetPaletteEntry",A_setPaletteEntry},{"vGetPaletteEntry",A_getPaletteEntry},
    {"vSetPalette",A_setPalette},{"vSetTransferMode",A_setTransferMode},
    {"vSetClipWindow",A_setClipWindow},{"vFillRect",A_fillRect},
    {"vDrawLine",A_drawLine},{"vPlot",A_plot},{"vGetPixel",A_getPixel},
    {"vUpdateSpriteMap",A_updateSpriteMap},{"vGetButtonData",A_getButtonData},
    {"vSpriteInit",A_spriteInit},{"vSpriteClear",A_spriteClear},
    {"vSpriteSet",A_spriteSet},{"vUpdateSprite",A_updateSprite},
    {"vDrawObject",A_drawObject},{"vDrawTile",A_drawTile},
    {"vUpdateSpriteRotate",A_updateSpriteRotate},
    {"vSpriteBoxCollision",A_spriteBoxCollision},{"vSpriteCollision",A_spriteCollision},
    {"vMapInit",A_mapInit},{"vMapHeaderUpdate",A_mapHeaderUpdate},
    {"vMapDispose",A_mapDispose},{"vUpdateMap",A_updateMap},{"vMapSetXY",A_mapSetXY},
    {"vMapGetAttribute",A_mapGetAttribute},{"vMapGetTile",A_mapGetTile},
    {"vMapSetTile",A_mapSetTile},{"vMapSetAttribute",A_mapSetAttribute},
    {"vSelectFont",A_selectFont},{"vSetActiveFont",A_setActiveFont},
    {"vTextOut",A_textOut},{"vTextOutU",A_textOutU},{"vPrint",A_print},
    {"vTextExtent",A_textExtent},{"vTextExtentU",A_textExtentU},
    {"vCharExtent",A_charExtent},{"vMsgBox",A_msgBox},{"vMsgBoxU",A_msgBoxU},
    {"vStrLen",A_strLen},{"vStrCpy",A_strCpy},{"vitoa",A_itoa},{"vutoa",A_utoa},
    {"vStrCat",A_strCat},{"vatoi",A_atoi},
    {"vDecompress",A_decompress},{"vDecompHdr",A_decompHdr},
    {"vStreamOpen",A_streamOpen},{"vStreamRead",A_streamRead},
    {"vStreamWrite",A_streamWrite},{"vStreamSeek",A_streamSeek},
    {"vStreamClose",A_streamClose},{"vPlayResource",A_playResource},
    {"vSoundInit",A_soundInit},{"vSoundGetHandle",A_soundGetHandle},
    {"vSoundLoad",A_soundGetHandle},{"vSoundCtrl",A_soundCtrl},
    {"vSoundCtrlEx",A_soundCtrlEx},{"vSoundDispose",A_soundDispose},
    {"vSoundDisposeHandle",A_soundDisposeHandle},{"vSoundUpload",A_soundUpload},
    {"vMusicInit",A_musicInit},{"vMusicLoad",A_musicLoad},
    {"vMusicCtrl",A_musicNoop},{"vMusicCtrlEx",A_musicNoop},
    {"vMusicDispose",A_musicNoop},{"vMusicDisposeHandle",A_musicNoop},
    {"vMusicClose",A_musicNoop},
    {"vCopyRect",A_copyRect},{"vSprintf",A_sprintf},{"vSprintfVa",A_sprintfVa},
    {"vFindRGBIndex",A_findRGBIndex},
    {"vMul16",A_mul16},{"vDiv16",A_div16},{"vSinF16",A_sinF16},{"vCosF16",A_cosF16},
    {"vLimit",A_limit},
    {"vGetSupportedRenderStates",A_getSupportedRenderStates},
    {"vSetDisplayWindow",A_setDisplayWindow},
    /* ---- 3D: the VMGP3D pipeline (Renderer3D module) ---- */
    {"vInit3D",A_init3D},{"vSetViewport",A_setViewport},{"vSetRenderState",A_setRenderState},
    {"vSetZBuffer",A_setZBuffer},{"vSetTexture",A_setTexture},{"vCreateTexture",A_createTexture},
    {"vSetActiveTexture",A_setActiveTexture},{"vSetFunctionalTexture",A_setFunctionalTexture},
    {"vDeleteTexture",A_deleteTexture},{"vDeleteAllTextures",A_deleteAllTextures},
    {"vFreeTexture",A_freeTexture},
    {"vRenderPrimitive",A_renderPrimitive},{"vRenderPrimitiveIndexed",A_renderPrimitiveIndexed},
    {"vDrawPolygon",A_drawPolygon},{"vDrawBillboard",A_drawBillboard},
    {"vDrawFlatPolygon",A_drawFlatPolygon},{"vGetInteger",A_getInteger},
    {"vSetMatrixMode",A_setMatrixMode},{"vMatrixIdentity",A_matrixIdentity},
    {"vMatrixSetCurrent",A_matrixSetCurrent},{"vMatrixGetCurrent",A_matrixGetCurrent},
    {"vMatrixMultiply",A_matrixMultiply},{"vMatrixSetProjection",A_matrixSetProjection},
    {"vMatrixSetLight",A_matrixSetLight},
    {"vMatrixTranslate",A_matrixTranslate},{"vMatrixScale",A_matrixScale},
    {"vMatrixRotateX",A_matrixRotateX},{"vMatrixRotateY",A_matrixRotateY},
    {"vMatrixRotateZ",A_matrixRotateZ},{"vMatrixRotateVector",A_matrixRotateVector},
    {"vMatrixPerspective",A_matrixPerspective},{"vMatrixOrtho",A_matrixOrtho},
    {"vMatrixLookAt",A_matrixLookAt},{"vMatrixInvert",A_matrixInvert},
    {"vMatrixTranspose",A_matrixTranspose},{"vMatrixMultiply3x3",A_matrixMultiply3x3},
    {"vVectorAdd",A_vectorAdd},{"vVectorSub",A_vectorSub},{"vVectorMul",A_vectorMul},
    {"vVectorNormalize",A_vectorNormalize},{"vCrossProduct",A_crossProduct},
    {"vDotProduct",A_dotProduct},
    {"vVectorTransformV3",A_vectorTransformV3},{"vVectorTransformV4",A_vectorTransformV4},
    {"vVectorProjectV3",A_vectorProjectV3},{"vVectorProjectV4",A_vectorProjectV4},
    {"vBoxInViewFrustum",A_boxInViewFrustum},{"vCreatePlaneFromPoly",A_createPlaneFromPoly},
    {"vCollisionPointBox",A_collisionPointBox},{"vCollisionBoxBox",A_collisionBoxBox},
    {"vCollisionVectorPoly",A_collisionVectorPoly},{"vCollisionVectorPlane",A_collisionVectorPlane},
    {"vSetLight",A_setLight},{"vSetMaterial",A_setMaterial},{"vSetMaterial2",A_setMaterial2},
    {"vSetAmbientLight",A_setAmbientLight},{"vSetCameraPos",A_setCameraPos},
    {"vResetLights",A_resetLights},{"vLightPoint",A_lightPoint},{"vSetFogColor",A_setFogColor},
    {"vGetZBufferValue",A_getZBufferValue},
    {"vVectorArrayAdd",A_vectorArrayAdd},{"vVectorArrayDelta",A_vectorArrayDelta},
    {"vPow",A_pow},{"vMul",A_mul},{"vDiv",A_div},{"vSqrt",A_sqrt},
    {"vSin",A_sin},{"vCos",A_cos},{"vTan",A_tan},
    {0,A_UNKNOWN}
};

int Sdk::resolve(const char* name){
    if (!name) return A_UNKNOWN;
    for (int i=0; kApi[i].name; i++)
        if (wpStrCmp(kApi[i].name, name)==0) return kApi[i].id;
    return A_UNKNOWN;
}

/* ================= construction ================= */
Sdk::Sdk(Pip2* vm_, Screen* scr_)
: fs(0), audio(0), vibra(0), vsound(vm_), r3d(0), soundEnabled(true),
  deviceModel(2), deviceVendor(3),          /* SonyEricssonT610 / SonyEricsson */
  videoFormat(7),                           /* 7 = RGB332, 256 direct colours */
  apiMinor(50), version150(false), stubCalls(0),
  vm(vm_), scr(scr_),
  nextHeap(0), taskStackSize(16384),
  freeList(0), freeCount(0), freeCap(0), freeBytes(0),
  foreColor(0xff), backColor(0), transferMode(1),   /* default = Transparent (nofun) */
  spriteSlotDraw(false),
  aFontPtr(0), aFontData(0), aFontChartbl(0), aFontBpp(0), aFontW(0), aFontH(0), aFontPal(0),
  rngState(0x12345678ULL), tick0(0),
  spriteCount(0), slotSprite(0), slotX(0), slotY(0),
  mapHeaderPtr(0),
  mhFlag(0), mhFmt(0), mhW(0), mhH(0), mhAnim(0),
  mhXPan(0), mhYPan(0), mhXPos(0), mhYPos(0), mhMapData(0), mhTileData(0),
  mhValid(false), frameAnimate(0), framePassed(0)
{
    nextHeap = vm->heapBase;
    freeCap = 32; freeList = new FreeBlock[freeCap];
    for (int i=0;i<MAX_STREAMS;i++){
        streams[i].used=false; streams[i].data=0; streams[i].len=0;
        streams[i].cap=0; streams[i].pos=0; streams[i].ownsData=false;
        streams[i].writable=false; streams[i].name[0]=0;
    }
    /* the game's "generation", as nofun calls it: importing vInit3D means SDK >= 1.50. */
    version150 = vm->exe->importsSymbol("vInit3D");
    if (scr && scr->host) tick0 = scr->host->TickMs();
}

Sdk::~Sdk(){
    delete[] freeList;
    delete[] slotSprite; delete[] slotX; delete[] slotY;
    for (int i=0;i<MAX_STREAMS;i++)
        if (streams[i].used && streams[i].ownsData) delete[] streams[i].data;
}

/* ================= the VM heap: bump allocation plus a free list ================= */
int Sdk::alloc(int size, bool clear){
    (void)clear;    /* blocks are ALWAYS zeroed: see the note below */
    if (size < 0) return 0;
    int sz = (size + 7) & ~7; if (sz == 0) sz = 8;
    /* 1) reuse a free block (first fit); split it if there is a remainder */
    for (int i=0;i<freeCount;i++){
        if (freeList[i].size >= sz){
            int p = freeList[i].ptr;
            if (freeList[i].size == sz){
                freeList[i] = freeList[--freeCount];
            } else {
                freeList[i].ptr  += sz;
                freeList[i].size -= sz;
            }
            freeBytes -= sz;
            liveBlocks.put(p, sz);
            /* ALWAYS zero a REUSED block, even when clear=false. Before the free list
               existed, every vNewPtr came from the bump pointer over pristine memory
               (i.e. zeros), and games rely on that -- fields they never initialise used
               to be 0. Handing back a dirty block delivered GARBAGE in those fields.
               Zeroing preserves the historical behaviour. */
            zero(p, sz);
            return p;
        }
    }
    /* 2) bump allocate */
    int p = nextHeap;
    if (p + sz > vm->heapEnd) return 0;
    nextHeap += sz;
    liveBlocks.put(p, sz);
    zero(p, sz);
    return p;
}

/* Frees a block: if it is at the TOP of the heap, nextHeap moves back and free blocks
   adjacent to the top are coalesced; otherwise it joins the free list for reuse. An
   unknown pointer is ignored. */
void Sdk::freePtr(int ptr){
    int sz = 0;
    if (!liveBlocks.remove(ptr, &sz)) return;
    if (ptr + sz == nextHeap){
        nextHeap = ptr;
        bool merged = true;
        while (merged){
            merged = false;
            for (int i=0;i<freeCount;i++){
                if (freeList[i].ptr + freeList[i].size == nextHeap){
                    nextHeap = freeList[i].ptr;
                    freeBytes -= freeList[i].size;
                    freeList[i] = freeList[--freeCount];
                    merged = true; break;
                }
            }
        }
    } else {
        if (freeCount == freeCap){
            int ncap = freeCap*2;
            FreeBlock* nf = new FreeBlock[ncap];
            for (int i=0;i<freeCount;i++) nf[i]=freeList[i];
            delete[] freeList; freeList=nf; freeCap=ncap;
        }
        freeList[freeCount].ptr = ptr;
        freeList[freeCount].size = sz;
        freeCount++;
        freeBytes += sz;
    }
}

void Sdk::zero(int ptr,int n){ for (int i=0;i<n;i++) vm->write8(ptr+i, 0); }

bool Sdk::plausiblePtr(int p) const {
    if (p < vm->base || p >= vm->base + vm->memLen) return false;
    return p < vm->heapBase || p < nextHeap;
}

/* ================= helpers de cor / pixel ================= */
/* resolve uma "cor" mophun p/ ARGB: bit31 setado = RGB555 direto; senao = indice de paleta */
u32 Sdk::getColor(int c) const {
    if (c < 0) return Screen::rgb555ToArgb(c & 0xffff);
    return scr->palette[c & 0xff];
}
void Sdk::px(int x,int y,u32 argb){ scr->put(x,y,argb); }

int Sdk::argbToRgb332(u32 argb) const {
    int r=(int)((argb>>16)&0xff), g=(int)((argb>>8)&0xff), b=(int)(argb&0xff);
    return ((r>>5)<<5) | ((g>>5)<<2) | (b>>6);
}
