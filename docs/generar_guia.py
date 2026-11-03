"""Genera una guía A4 de dos páginas con esquemas vectoriales de la interfaz.
Requiere reportlab. Las pantallas se dibujan a partir de las capturas aportadas;
no son capturas de una sesión Android ni fotografías retocadas.
"""
from pathlib import Path
from math import sin, cos, pi
from reportlab.pdfgen import canvas
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import Paragraph
from reportlab.lib.styles import ParagraphStyle

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'docs/SoinuBiderXL-guia-rapida.pdf'
FONT = Path('/usr/share/fonts/truetype/dejavu')
pdfmetrics.registerFont(TTFont('Guide', str(FONT / 'DejaVuSans.ttf')))
pdfmetrics.registerFont(TTFont('GuideBold', str(FONT / 'DejaVuSans-Bold.ttf')))
pdfmetrics.registerFontFamily('Guide', normal='Guide', bold='GuideBold')
W, H = A4
INK = colors.HexColor('#25212F')
MUTED = colors.HexColor('#625B6B')
PURPLE = colors.HexColor('#704D98')
PALE = colors.HexColor('#F3EFF8')
BORDER = colors.HexColor('#E2DBEA')
BG = colors.HexColor('#19171F')
CTRL = colors.HexColor('#352D40')
LIT = colors.HexColor('#B08ADC')
c = canvas.Canvas(str(OUTPUT), pagesize=A4, pageCompression=1)
c.setTitle('SoinuBider XL · Guía rápida de uso')
c.setAuthor('SoinuBider XL')
c.setSubject('Android: grabación de loops, gestos, ajustes y sincronización de fase SYNC')
boxes = []

def text(value, x, top, size=11, color=INK, bold=False):
    c.setFont('GuideBold' if bold else 'Guide', size)
    c.setFillColor(color)
    c.drawString(x, H - top - size, value)

def paragraph(value, x, top, width, size=10.2, leading=14, color=INK):
    style = ParagraphStyle('p', fontName='Guide', fontSize=size, leading=leading,
                           textColor=color, spaceAfter=0)
    p = Paragraph(value, style)
    _, height = p.wrap(width, H)
    p.drawOn(c, x, H - top - height)
    boxes.append((c.getPageNumber(), x, top, width, height, value))
    assert top + height < H - 22, (top, height, value)
    return height

def rounded(x, top, width, height, fill, stroke=None, radius=10):
    c.setFillColor(fill)
    c.setStrokeColor(stroke or fill)
    c.setLineWidth(.7)
    c.roundRect(x, H - top - height, width, height, radius, fill=1, stroke=bool(stroke))

def badge(label, x, top, r=10):
    c.setFillColor(PURPLE)
    c.circle(x, H - top, r, fill=1, stroke=0)
    c.setFillColor(colors.white)
    c.setFont('GuideBold', r * 1.05)
    c.drawCentredString(x, H - top - r * .37, str(label))

def header(subtitle, page):
    text('SoinuBider XL', 34, 27, 25, bold=True)
    text(subtitle, 35, 65, 12, MUTED)
    c.setStrokeColor(BORDER)
    c.line(35, H-89, W-35, H-89)
    text('GUÍA DE USO · ANDROID', W-191, 35, 8.3, PURPLE, True)
    c.setStrokeColor(BORDER)
    c.line(35, 31, W-35, 31)
    text('Versión con SYNC · noviembre de 2026', 35, H-25, 7.7, MUTED)
    text(f'{page} / 2', W-58, H-25, 8, MUTED)

def callout(label, title, body, top, x=267, width=292, size=10.2):
    badge(label, x-16, top+8, 9)
    text(title, x, top, 11.1, bold=True)
    height = paragraph(body, x, top+20, width, size, 14)
    return top + 20 + height

# Coordenadas de pantalla 720 × 1600, siguiendo las capturas del usuario.
def screen(x, top, width, settings=False):
    height = width * 1600 / 720
    c.saveState()
    c.translate(x, H-top-height)
    c.scale(width/720, width/720)
    def rect(px, py, pw, ph, color, border=None, radius=18):
        c.setFillColor(color)
        c.setStrokeColor(border or color)
        c.setLineWidth(2)
        c.roundRect(px, 1600-py-ph, pw, ph, radius, fill=1, stroke=bool(border))
    def label(s, px, py, size=35, center=False, color=colors.white, bold=False):
        c.setFont('GuideBold' if bold else 'Guide', size)
        c.setFillColor(color)
        (c.drawCentredString if center else c.drawString)(px, 1600-py, s)
    def mark(s, px, py):
        c.setFillColor(LIT)
        c.circle(px, 1600-py, 29, fill=1, stroke=0)
        label(str(s), px, py+11, 33, True, BG, True)
    rect(0, 0, 720, 1600, BG, colors.HexColor('#655C70'), 28)
    label('19:11' if not settings else '19:12', 43, 43, 25, bold=True)
    label('84 %', 607, 43, 20)
    c.setStrokeColor(colors.HexColor('#CCC7D2'))
    c.setLineWidth(3)
    c.circle(360, 1600-1550, 15, fill=0, stroke=1)
    rect(200, 1538, 22, 22, colors.HexColor('#CCC7D2'), radius=2)
    p=c.beginPath();p.moveTo(518,1600-1534);p.lineTo(518,1600-1566);p.lineTo(491,1600-1550);p.close()
    c.setFillColor(colors.HexColor('#CCC7D2'));c.drawPath(p,fill=1,stroke=0)
    if not settings:
        label('SoinuBider XL', 360, 180, 49, True)
        c.setStrokeColor(colors.HexColor('#B9B6B0'));c.setLineWidth(12)
        for j in range(12):
            a=j*pi/6;c.line(652+19*cos(a),1600-164+19*sin(a),652+29*cos(a),1600-164+29*sin(a))
        c.setFillColor(colors.HexColor('#B9B6B0'));c.circle(652,1600-164,23,fill=1,stroke=0)
        c.setFillColor(BG);c.circle(652,1600-164,10,fill=1,stroke=0)
        for i in range(20):
            color = '#41E77D' if i<10 else ('#183C2B' if i<14 else '#4B3A20' if i<17 else '#4E2731')
            c.setFillColor(colors.HexColor(color));c.circle(40+i*33.6,1600-301,11.5,fill=1,stroke=0)
        c.setStrokeColor(colors.HexColor('#554D60'));c.setLineWidth(2)
        c.rect(24,1600-597,672,221,fill=0,stroke=1)
        rect(309, 356, 102, 38, BG, radius=0);label('BPM',360,390,29,True)
        rect(44,414,166,160,CTRL,colors.HexColor('#665B71'))
        rect(510,414,166,160,colors.HexColor('#6D5688'),colors.HexColor('#DAD2E7'))
        label('AUTO',127,508,35,True);label('TAP',593,508,35,True)
        label('120,0',360,515,53,True)
        rect(24,618,326,132,CTRL,colors.HexColor('#665B71'))
        rect(368,618,326,132,CTRL,colors.HexColor('#665B71'))
        label('Borrar beats',187,697,33,True);label('Reset BPM',531,697,33,True)
        titles=['1 beats','2 beats','4 beats','4 beats','8 beats','16 beats']
        for i,title in enumerate(titles):
            bx=24+(i%3)*230;by=772+(i//3)*288
            rect(bx,by,214,266,colors.HexColor('#49335E') if i==0 else colors.HexColor('#292530'),colors.HexColor('#514958'))
            label(title,bx+107,by+146,35,True)
        rect(24,1348,326,130,CTRL,colors.HexColor('#665B71'))
        rect(368,1348,326,130,CTRL,colors.HexColor('#665B71'))
        label('<<',187,1428,36,True);label('>>',531,1428,36,True)
        for m,px,py in [(1,680,266),(2,282,376),(3,678,791),(4,218,791),(5,322,638),(6,666,638),(7,360,1410)]:mark(m,px,py)
    else:
        label('Ajustes',40,158,49);label('Cerrar ×',548,155,27)
        label('Desfase actual: +43 ms',40,275,39)
        rect(40,343,305,82,CTRL,colors.HexColor('#665B71'),41);label('Usar valor actual',192,395,27,True)
        label('Desfase guardado: +43 ms',40,516,38)
        rect(40,583,273,82,CTRL,colors.HexColor('#665B71'),41);label('Resetear valor',176,636,27,True)
        label('Rango BPM Auto',40,781,33)
        for dx,value in [(408,'115'),(553,'135')]:
            rect(dx,720,127,94,CTRL,colors.HexColor('#665B71'))
            label(value,dx+21,780,36);label('▾',dx+96,776,25)
        label('Apariencia',40,916,39)
        palettes=[['#19171F','#302C39','#48414F','#41364F','#69458C','#9466C2','#B08ADC'],
                  ['#191B20','#2C323B','#424D5C','#35465B','#24578A','#397FC0','#75B9F1'],
                  ['#1C1B19','#34312D','#56504A','#514333','#805929','#AD7A39','#DFAD64'],
                  ['#1A1B1E','#303237','#50535A','#45484F','#626771','#828892','#B1B6BE']]
        for i,palette in enumerate(palettes):
            bx=40+i*165
            clip=c.beginPath();clip.roundRect(bx,1600-1054,145,102,15)
            c.saveState();c.clipPath(clip,stroke=0,fill=0)
            for j,color in enumerate(palette):
                c.setFillColor(colors.HexColor(color));c.rect(bx+j*145/7,1600-1054,145/7+1,102,fill=1,stroke=0)
            c.restoreState();c.setStrokeColor(colors.white if i==0 else colors.HexColor('#665B71'));c.setLineWidth(4 if i==0 else 2);c.roundRect(bx,1600-1054,145,102,15,stroke=1,fill=0)
        atlas=ROOT/'app/src/main/res/drawable-nodpi/soinu_texture_atlas.png'
        for i in range(4):
            bx=40+i*165;bottom=1600-1182
            clip=c.beginPath();clip.roundRect(bx,bottom,145,102,15)
            c.saveState();c.clipPath(clip,stroke=0,fill=0)
            if i<3:
                # Composición PDF con recorte de los materiales existentes, sin alterar la imagen fuente.
                col=i%2;row=i//2
                c.drawImage(str(atlas),bx-col*290,bottom-(1-row)*204,width=580,height=408,mask='auto')
            else:
                c.setFillColor(colors.black);c.rect(bx,bottom,145,102,fill=1,stroke=0)
                for j in range(9):
                    cx=bx+16+(j%3)*49;cy=bottom+17+(j//3)*35;r=7+j%3*2
                    c.setStrokeColor(colors.HexColor(['#49C6EE','#B48BF0','#FF738A','#4ED5B0','#F3BE58'][j%5]));c.setLineWidth(.8)
                    if j%4==0:c.circle(cx,cy,r,stroke=1,fill=0)
                    else:
                        n=[3,4,5][j%3];path=c.beginPath()
                        for k in range(n):
                            a=2*pi*k/n-pi/2;point=(cx+r*cos(a),cy+r*sin(a))
                            (path.moveTo if k==0 else path.lineTo)(*point)
                        path.close();c.drawPath(path,stroke=1,fill=0)
            c.restoreState();c.setStrokeColor(colors.HexColor('#665B71'));c.setLineWidth(2);c.roundRect(bx,bottom,145,102,15,fill=0,stroke=1)
        label('Transparencia de botones: 45 %',40,1267,31)
        rect(44,1328,632,32,colors.HexColor('#4E425D'),radius=16)
        rect(44,1328,285,32,LIT,radius=16)
        for i in range(21):
            c.setFillColor(colors.HexColor('#74627F'));c.circle(55+i*30.2,1600-1344,4,fill=1,stroke=0)
        rect(325,1301,9,85,LIT,radius=4)
        for m,px,py in [('A',663,246),('B',663,491),('C',680,690),('D',663,894),('E',663,1223)]:mark(m,px,py)
    c.restoreState()
    return height

def arrow(x1, top1, x2, top2, color=PURPLE):
    c.setStrokeColor(color);c.setFillColor(color);c.setLineWidth(1.7)
    y1=H-top1;y2=H-top2;c.line(x1,y1,x2,y2)
    a=__import__('math').atan2(y2-y1,x2-x1)
    p=c.beginPath();p.moveTo(x2,y2);p.lineTo(x2-6*cos(a-.5),y2-6*sin(a-.5));p.lineTo(x2-6*cos(a+.5),y2-6*sin(a+.5));p.close();c.drawPath(p,fill=1,stroke=0)

def mini_button(label,x,top,width=52,height=42):
    rounded(x,top,width,height,CTRL,radius=6)
    c.setFillColor(colors.white);c.setFont('Guide',10);c.drawCentredString(x+width/2,H-top-height/2-3.5,label)

header('Empieza a crear loops',1)
rounded(35,101,W-70,80,PALE,radius=10)
text('Antes de empezar',48,110,10.6,PURPLE,True)
text('Con tarjeta: música → tarjeta USB → móvil (OTG)',48,130,11.2,bold=True)
paragraph('El audio se inicia al abrir; permite el micrófono. Escucha desde la tarjeta o usa el micro del móvil.',48,150,W-96,9.2,12)
screen(35,190,207)
paragraph('Esquema basado en tu pantalla. Los colores cambian con la apariencia elegida.',39,659,201,9,12,MUTED)
callout(1,'Comprueba la entrada','Los LED deben moverse. Evita el rojo continuo: baja el nivel de la señal que entra.',191)
callout(2,'Obtén el tempo','<b>AUTO:</b> deja sonar un ritmo estable unos 10–15 segundos.<br/><b>TAP:</b> pulsa al menos <b>8 veces</b>, siguiendo el pulso. Una pausa de más de 3 segundos reinicia la cuenta.',253)
callout(3,'Graba un loop','Toca un banco vacío. La captura comienza en el siguiente pulso y, al terminar, el loop se repite automáticamente.<br/><b>1 beat = 1 pulso musical.</b>',365)
callout(4,'Pausa y vuelve a sonar','Un toque corto en un banco que suena lo pausa. Otro toque lo reanuda.',460)
callout(5,'Borra uno o todos','<b>Todos:</b> mantén <b>Borrar beats 2 segundos</b>.<br/><b>Uno:</b> primero páusalo; mantén ese banco y toca <b>Borrar beats</b> con otro dedo.',524)
callout(6,'Reinicia el tempo','Mantén <b>Reset BPM 2 segundos</b>. Reinicia AUTO/TAP; los loops grabados continúan. También desactiva SYNC.',617)
rounded(35,710,W-70,87,PALE,radius=10)
badge(7,51,728,9)
text('Flechas: corrige el desfase',69,719,11.2,bold=True)
paragraph('<b>&lt;&lt; retrasa · &gt;&gt; adelanta.</b> Un toque ajusta 1 ms; mantener la flecha repite el ajuste. Sin sujetar un banco, afecta a <b>todos</b> los loops grabados, también los pausados, y desactiva SYNC. Para corregir solo uno, usa el gesto de la página 2.',48,741,W-96,9.7,13)
c.showPage()

header('Ajustes y gestos con los dedos',2)
screen(35,123,160,True)
paragraph('Ejemplo: tus valores pueden ser distintos. Abre Ajustes con la rueda dentada.',38,489,162,8.7,11.5,MUTED)
callout('A','Desfase actual', 'Es el ajuste del banco mantenido o del último que has puesto a sonar. <b>Usar valor actual</b> guarda ese número.',123,x=236,width=323,size=10)
callout('B','Desfase guardado', 'Se aplica a las próximas grabaciones. <b>Resetear valor</b> guarda 0 ms; no desplaza los loops ya grabados.',206,x=236,width=323,size=10)
callout('C','Rango BPM Auto', 'Elige el mínimo y el máximo que buscará AUTO. Van de 10 en 10 y mantienen una separación de al menos <b>20 BPM</b>.',290,x=236,width=323,size=10)
callout('D','Apariencia', 'Toca una muestra para cambiar las dos pantallas. Los LED mantienen sus colores verde, ámbar y rojo.',373,x=236,width=323,size=10)
callout('E','Transparencia', '<b>0 %:</b> relleno opaco. <b>100 %:</b> relleno transparente. El texto y los bordes siguen visibles.',440,x=236,width=323,size=10)
text('Tres gestos que conviene conocer',35,531,16,bold=True)
text('En los gestos de dos dedos: mantén 1 y toca 2 sin soltar.',35,554,9.4,MUTED)
cardtop=578;cardheight=195;cardwidth=(W-90)/3
for i,title in enumerate(['Dividir y restaurar','Ajustar un banco','Activar / quitar SYNC']):
    x=35+i*(cardwidth+10)
    rounded(x,cardtop,cardwidth,cardheight,PALE,BORDER,9)
    text(title,x+11,cardtop+11,10.5,PURPLE,True)
    if i==0:
        mini_button('4 beats',x+51,cardtop+48,65,52)
        badge(1,x+83,cardtop+78,8)
        arrow(x+34,cardtop+78,x+34,cardtop+38)
        arrow(x+134,cardtop+78,x+134,cardtop+113)
        text('÷2',x+16,cardtop+57,9,PURPLE,True)
        text('×2',x+137,cardtop+87,9,PURPLE,True)
        body='Con el banco sonando, arrastra el dedo <b>fuera por arriba</b>: mitad de duración. <b>Fuera por abajo:</b> doble, hasta la original. Un cambio por gesto.'
    elif i==1:
        mini_button('Banco',x+12,cardtop+56,64,46)
        mini_button('<<  >>',x+94,cardtop+56,58,46)
        badge(1,x+44,cardtop+99,8);badge(2,x+123,cardtop+99,8)
        arrow(x+80,cardtop+78,x+89,cardtop+78)
        body='<b>Mantén el banco que suena.</b> Con otro dedo, toca una flecha. Solo se corrige ese banco. Si tenía SYNC, lo conserva con el nuevo desfase.'
    else:
        mini_button('Banco',x+12,cardtop+56,64,46)
        mini_button('AUTO',x+94,cardtop+56,58,46)
        badge(1,x+44,cardtop+99,8);badge(2,x+123,cardtop+99,8)
        arrow(x+80,cardtop+78,x+89,cardtop+78)
        body='<b>Cuadra antes el loop.</b> Mantén su banco y toca AUTO. Repite para desactivar.<br/><b>SYNC…</b> espera; <b>SYNC</b> sigue el pulso. Dividir y usar flechas conserva SYNC.'
    height=paragraph(body,x+11,cardtop+116,cardwidth-22,9,12)
    assert height <= cardheight-121, (title,height)
paragraph('<b>SYNC corrige pequeñas derivas, sin cambiar el BPM del loop.</b> Si falta una referencia fiable o el tempo cambia demasiado, queda en espera. Pausa, Reset BPM o TAP lo desactivan.',35,783,W-70,8.6,11.5,MUTED)
c.save()
print(OUTPUT)
print('Páginas: 2 · bloques de texto:',len(boxes))
