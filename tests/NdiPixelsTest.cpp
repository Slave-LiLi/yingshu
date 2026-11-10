#include "../native/ndi_pixels.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int assertions;
static void check(bool condition,const char* name){
    ++assertions;
    if(!condition){fprintf(stderr,"FAIL: %s\n",name);exit(1);}
}
static unsigned char red(int x,int y){return static_cast<unsigned char>(x*13+y*3);}
static unsigned char green(int x,int y){return static_cast<unsigned char>(x*7+y*11);}
static unsigned char blue(int x,int y){return static_cast<unsigned char>(x*5+y*17);}

// Independent reference: the original per-pixel nearest-sample formula. Compare
// the entire destination allocation, including row padding and guard bytes.
static void referenceCopy(const NdiRgbSource& source,const NdiRgbRegion& region,
                          unsigned char* output,int width,int height,int stride){
    for(int y=0;y<height;++y){
        int sy=region.top+static_cast<int>(static_cast<int64_t>(y)*region.height/height);
        for(int x=0;x<width;++x){
            int sx=region.left+static_cast<int>(static_cast<int64_t>(x)*region.width/width);
            const unsigned char* p=source.pixels+static_cast<size_t>(sy)*source.stride+sx*4;
            unsigned char* d=output+static_cast<size_t>(y)*stride+x*4;
            d[0]=p[source.blueFirst?2:0];d[1]=p[1];d[2]=p[source.blueFirst?0:2];d[3]=255;
        }
    }
}

static void differentialCases(){
    struct Case{int sourceWidth,sourceHeight,left,top,width,height,outWidth,outHeight;};
    const Case cases[]={
        {1920,1080,1472,936,320,128,320,128}, // production model crop
        {1920,1080,0,0,1920,1080,960,540},   // production preview
        {83,59,5,7,72,45,24,9},             // unequal integer x/y factors
        {83,59,5,7,72,45,72,45},            // unscaled crop with offsets
        {83,59,5,7,71,43,29,17},            // both dimensions noninteger
        {83,59,5,7,72,43,24,17},            // only x integer
        {83,59,5,7,71,45,29,9},             // only y integer
        {19,17,2,3,11,9,22,18},             // upscaled: generic formula
        {19,17,2,3,11,9,1,1},               // one-pixel output
        {19,17,18,16,1,1,1,1},              // last source pixel
    };
    const int paddings[]={0,3,28};
    for(const Case& c:cases)for(int blueFirst=0;blueFirst<2;++blueFirst){
        for(int padding:paddings){
            const int sourceStride=c.sourceWidth*4+padding;
            const int destinationStride=c.outWidth*4+padding+1;
            const size_t sourceBytes=static_cast<size_t>(sourceStride)*c.sourceHeight+2;
            const size_t destinationBytes=static_cast<size_t>(destinationStride)*c.outHeight+2;
            unsigned char* storage=static_cast<unsigned char*>(malloc(sourceBytes));
            unsigned char* actual=static_cast<unsigned char*>(malloc(destinationBytes));
            unsigned char* expected=static_cast<unsigned char*>(malloc(destinationBytes));
            check(storage&&actual&&expected,"differential allocations");
            memset(storage,0xA5,sourceBytes);memset(actual,0xCC,destinationBytes);memset(expected,0xCC,destinationBytes);
            // Offset the source and destination to exercise unaligned addresses.
            unsigned char* pixels=storage+1;
            for(int y=0;y<c.sourceHeight;++y)for(int x=0;x<c.sourceWidth;++x){
                unsigned char* p=pixels+static_cast<size_t>(y)*sourceStride+x*4;
                p[0]=red(x,y);p[1]=green(x,y);p[2]=blue(x,y);p[3]=static_cast<unsigned char>(x+y);
            }
            NdiRgbSource input{pixels,c.sourceWidth,c.sourceHeight,sourceStride,blueFirst!=0};
            NdiRgbRegion region{c.left,c.top,c.width,c.height};
            referenceCopy(input,region,expected+1,c.outWidth,c.outHeight,destinationStride);
            check(ndiCopyRgb(input,region,actual+1,c.outWidth,c.outHeight,destinationStride),"differential copy succeeds");
            check(memcmp(actual,expected,destinationBytes)==0,"all output bytes equal generic reference including guards and padding");
            free(expected);free(actual);free(storage);
        }
    }
}

int main(){
    const int width=1920,height=1080,stride=width*4+28;
    unsigned char* source=static_cast<unsigned char*>(malloc(static_cast<size_t>(stride)*height));
    check(source!=nullptr,"source allocation");
    NdiRgbRegion panel{};
    check(ndiTrialPanel(width,height,&panel),"1080p panel recognized");
    check(panel.left==1472&&panel.top==936&&panel.width==320&&panel.height==128,"manifest panel geometry");
    NdiRgbRegion untouched{7,8,9,10};
    check(!ndiTrialPanel(1280,720,&untouched)&&untouched.left==7,"non1080p rejected without mutation");
    check(!ndiTrialPanel(1920,1080,nullptr),"null panel output rejected");
    int previewWidth,previewHeight;
    ndiPreviewSize(width,height,&previewWidth,&previewHeight);
    check(previewWidth==960&&previewHeight==540,"preview is half linear resolution");
    int smallWidth,smallHeight;ndiPreviewSize(320,128,&smallWidth,&smallHeight);
    check(smallWidth==320&&smallHeight==128,"small source not enlarged");
    const int roiStride=panel.width*4+12,previewStride=previewWidth*4+16;
    unsigned char* roi=static_cast<unsigned char*>(malloc(static_cast<size_t>(roiStride)*panel.height));
    unsigned char* preview=static_cast<unsigned char*>(malloc(static_cast<size_t>(previewStride)*previewHeight));
    check(roi&&preview,"output allocations");
    for(int blueFirst=0;blueFirst<2;++blueFirst){
        // Opaque and nonopaque source alpha must preserve identical RGB detection input.
        for(int varyingAlpha=0;varyingAlpha<2;++varyingAlpha){
            memset(source,0xA5,static_cast<size_t>(stride)*height);
            for(int y=0;y<height;++y)for(int x=0;x<width;++x){
                unsigned char* pixel=source+static_cast<size_t>(y)*stride+x*4;
                pixel[blueFirst?2:0]=red(x,y);pixel[1]=green(x,y);pixel[blueFirst?0:2]=blue(x,y);
                pixel[3]=varyingAlpha?static_cast<unsigned char>(x+y):255;
            }
            NdiRgbSource input{source,width,height,stride,blueFirst!=0};
            memset(roi,0xCC,static_cast<size_t>(roiStride)*panel.height);
            memset(preview,0xCC,static_cast<size_t>(previewStride)*previewHeight);
            check(ndiCopyRgb(input,panel,roi,panel.width,panel.height,roiStride),"panel copy succeeds");
            bool roiCorrect=true,paddingUntouched=true;
            for(int y=0;y<panel.height;++y){
                for(int x=0;x<panel.width;++x){
                    unsigned char* p=roi+static_cast<size_t>(y)*roiStride+x*4;
                    int sx=x+panel.left,sy=y+panel.top;
                    roiCorrect=roiCorrect&&p[0]==red(sx,sy)&&p[1]==green(sx,sy)&&p[2]==blue(sx,sy)&&p[3]==255;
                }
                for(int i=panel.width*4;i<roiStride;++i)paddingUntouched=paddingUntouched&&roi[y*roiStride+i]==0xCC;
            }
            check(roiCorrect,"all 40960 panel pixels equal original RGB crop");
            check(paddingUntouched,"panel row padding preserved");
            check(ndiCopyRgb(input,{0,0,width,height},preview,previewWidth,previewHeight,previewStride),"preview copy succeeds");
            bool previewCorrect=true;
            for(int y=0;y<previewHeight;++y)for(int x=0;x<previewWidth;++x){
                unsigned char* p=preview+static_cast<size_t>(y)*previewStride+x*4;
                previewCorrect=previewCorrect&&p[0]==red(x*2,y*2)&&p[1]==green(x*2,y*2)&&p[2]==blue(x*2,y*2)&&p[3]==255;
            }
            check(previewCorrect,"preview sampling covers exact source coordinates");
            check(!ndiCopyRgb(input,{-1,0,320,128},roi,320,128,roiStride),"negative crop rejected");
            check(!ndiCopyRgb(input,{1900,1000,320,128},roi,320,128,roiStride),"out of bounds crop rejected");
            check(!ndiCopyRgb(input,panel,roi,320,128,100),"short destination stride rejected");
            check(!ndiCopyRgb({source,width,height,20,false},panel,roi,320,128,roiStride),"short source stride rejected");
        }
    }
    free(preview);free(roi);free(source);
    differentialCases();
    printf("NdiPixelsTest: %d assertions passed; exact ROI/preview and integer/noninteger differential cases with unaligned buffers\n",assertions);
    return 0;
}
