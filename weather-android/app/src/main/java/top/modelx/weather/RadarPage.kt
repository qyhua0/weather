/**
 * 降水雷达页：WebView 渲染 RainViewer 雷达帧动画与高德底图。
 *
 * @author zhenhua
 * @version 1.2
 */
package top.modelx.weather

/** 雷达页：纯 canvas 瓦片地图（OSM 底图 + RainViewer 雷达叠加 + 播放条），不依赖任何地图库。 */
object RadarPage {

    fun buildHtml(frames: List<String>, frameTimes: List<String>, lat: Double, lon: Double): String {
        val wx = (lon + 180.0) / 360.0
        val latRad = Math.toRadians(lat)
        val wy = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0
        return HTML
            .replace("__FRAMES__", frames.joinToString(",", "[", "]"))
            .replace("__TIMES__", frameTimes.joinToString(",", "[", "]"))
            .replace("__WX__", String.format("%.6f", wx))
            .replace("__WY__", String.format("%.6f", wy))
    }

    private const val OSM = "https://tile.openstreetmap.org"

    private const val HTML = """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<style>html,body{margin:0;padding:0;background:#0e1a2b;overflow:hidden;height:100%}
#c{display:block;touch-action:none}
.bar{position:fixed;left:0;right:0;bottom:0;background:rgba(8,18,38,.88);color:#fff;
display:flex;align-items:center;gap:8px;padding:8px 10px;font-size:13px}
.bar button{background:#1e3a68;color:#fff;border:0;border-radius:6px;padding:6px 12px;font-size:15px}
#seek{flex:1}#t{min-width:88px;text-align:right}</style></head><body>
<canvas id="c"></canvas>
<div class="bar"><button id="play">&#9208;</button><input id="seek" type="range" min="0" max="0" value="0">
<span id="t"></span><button id="zo">&minus;</button><button id="zi">+</button></div>
<script>
var FRAMES=__FRAMES__,TIMES=__TIMES__,OSM="https://webrd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}";
var wx=__WX__,wy=__WY__,z=5,idx=0,playing=true,timer=null,cache={};
var cv=document.getElementById('c'),g=cv.getContext('2d'),W=0,H=0,DPR=window.devicePixelRatio||1;
function resize(){W=window.innerWidth;H=window.innerHeight-52;cv.width=W*DPR;cv.height=H*DPR;
cv.style.width=W+'px';cv.style.height=H+'px';draw();}
window.addEventListener('resize',resize);
function tile(u){var im=cache[u];if(im)return im;im=new Image();im.ok=false;
im.onload=function(){im.ok=true;draw()};im.onerror=function(){im.ok=false};im.src=u;cache[u]=im;return im}
function urlOf(t,x,y,zm){return t.replace('{z}',zm).replace('{x}',x).replace('{y}',y)}
function draw(){g.setTransform(DPR,0,0,DPR,0,0);g.fillStyle='#0e1a2b';g.fillRect(0,0,W,H);
var full=256*Math.pow(2,z),ox=wx*full-W/2,oy=wy*full-H/2,n=Math.pow(2,z);
var x0=Math.floor(ox/256),x1=Math.floor((ox+W)/256),y0=Math.floor(oy/256),y1=Math.floor((oy+H)/256);
var x,y,wxm;
for(y=y0;y<=y1;y++){if(y<0||y>=n)continue;for(x=x0;x<=x1;x++){wxm=((x%n)+n)%n;
var im=tile(urlOf(OSM,wxm,y,z));
if(im.ok&&im.naturalWidth>0)g.drawImage(im,x*256-ox,y*256-oy,256,256);}}
if(FRAMES.length){g.globalAlpha=0.62;var f=FRAMES[Math.min(idx,FRAMES.length-1)];
for(y=y0;y<=y1;y++){if(y<0||y>=n)continue;for(x=x0;x<=x1;x++){wxm=((x%n)+n)%n;
var r=tile(urlOf(f,wxm,y,z));
if(r.ok&&r.naturalWidth>0)g.drawImage(r,x*256-ox,y*256-oy,256,256);}}g.globalAlpha=1;}
}
var lx=0,ly=0,drag=false;
cv.addEventListener('touchstart',function(e){drag=true;lx=e.touches[0].clientX;ly=e.touches[0].clientY});
cv.addEventListener('touchmove',function(e){if(!drag)return;
var full=256*Math.pow(2,z);wx-=(e.touches[0].clientX-lx)/full;wy-=(e.touches[0].clientY-ly)/full;
lx=e.touches[0].clientX;ly=e.touches[0].clientY;draw()},{passive:true});
cv.addEventListener('touchend',function(){drag=false});
document.getElementById('zi').onclick=function(){z=Math.min(9,z+1);draw()};
document.getElementById('zo').onclick=function(){z=Math.max(2,z-1);draw()};
var seek=document.getElementById('seek'),tt=document.getElementById('t');
seek.max=Math.max(0,FRAMES.length-1);
function show(i){idx=i;seek.value=i;tt.textContent=TIMES[i]||'';draw();
var half=FRAMES.length>0&&i>=Math.floor(FRAMES.length*0.7);}
seek.oninput=function(){pause();show(parseInt(seek.value))};
function play(){playing=true;document.getElementById('play').innerHTML='&#9208;';
timer=setInterval(function(){show((idx+1)%FRAMES.length)},600)}
function pause(){playing=false;document.getElementById('play').innerHTML='&#9654;';
if(timer)clearInterval(timer)}
document.getElementById('play').onclick=function(){playing?pause():play()};
if(FRAMES.length){var st=Math.floor(FRAMES.length*0.7);show(st);play();}
resize();
</script></body></html>"""
}
