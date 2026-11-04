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
INK = colors.HexColor('#29241F')
MUTED = colors.HexColor('#655B51')
ACCENT = colors.HexColor('#B34600')
PALE = colors.HexColor('#FFF2E8')
BORDER = colors.HexColor('#E8D7C9')
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
    c.setFillColor(ACCENT)
    c.circle(x, H - top, r, fill=1, stroke=0)
    c.setFillColor(colors.white)
    c.setFont('GuideBold', r * 1.05)
    c.drawCentredString(x, H - top - r * .37, str(label))

def header(subtitle, page):
    text('SoinuBider XL', 34, 27, 25, bold=True)
    text(subtitle, 35, 65, 12, MUTED)
    c.setStrokeColor(BORDER)
    c.line(35, H-89, W-35, H-89)
    text('GUÍA DE USO · ANDROID', W-191, 35, 8.3, ACCENT, True)
    c.setStrokeColor(BORDER)
    c.line(35, 31, W-35, 31)
    text('Versión con SYNC · noviembre de 2026', 35, H-25, 7.7, MUTED)
    text(f'{page} / 2', W-58, H-25, 8, MUTED)

def callout(label, title, body, top, x=267, width=292, size=10.2, leading=14):
    badge(label, x-16, top+8, 9)
    text(title, x, top, 11.1, bold=True)
    height = paragraph(body, x, top+20, width, size, leading)
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
        c.setFillColor(ACCENT)
        c.setStrokeColor(colors.white)
        c.setLineWidth(4)
        c.circle(px, 1600-py, 29, fill=1, stroke=1)
        label(str(s), px, py+11, 33, True, colors.white, True)
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
        for m,px,py in [(1,282,376),(2,678,791),(3,218,791),(4,322,638),(5,666,638),(6,360,1410)]:mark(m,px,py)
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
        rect(40,1430,640,70,CTRL,colors.HexColor('#665B71'),25)
        label('Manual de instrucciones',360,1476,28,True)
        for m,px,py in [('A',663,246),('B',663,491),('C',680,690),('D',663,894),('E',663,1223),('F',663,1405)]:mark(m,px,py)
    c.restoreState()
    return height

def arrow(x1, top1, x2, top2, color=ACCENT):
    c.setStrokeColor(color);c.setFillColor(color);c.setLineWidth(1.7)
    y1=H-top1;y2=H-top2;c.line(x1,y1,x2,y2)
    a=__import__('math').atan2(y2-y1,x2-x1)
    p=c.beginPath();p.moveTo(x2,y2);p.lineTo(x2-6*cos(a-.5),y2-6*sin(a-.5));p.lineTo(x2-6*cos(a+.5),y2-6*sin(a+.5));p.close();c.drawPath(p,fill=1,stroke=0)

def mini_button(label,x,top,width=52,height=42):
    rounded(x,top,width,height,CTRL,radius=6)
    c.setFillColor(colors.white);c.setFont('Guide',10);c.drawCentredString(x+width/2,H-top-height/2-3.5,label)

header('Controles y gestos',1)
paragraph('Música → entrada 1–2 de la tarjeta → móvil (OTG). El audio se inicia al abrir.',
          35,104,W-70,10,13,MUTED)
screen(35,136,184)
paragraph('<b>LED:</b> nivel de entrada. Evita el rojo continuo.',39,551,180,8.9,10.5,MUTED)
controls = [
    (1, 'Tempo', '<b>AUTO:</b> detecta el ritmo de la música.<br/><b>TAP:</b> marca al menos <b>8 pulsaciones</b>. Una pausa de más de 3 s reinicia la cuenta.', 136),
    (2, 'Grabar', 'Toca un botón vacío. Graba desde el siguiente pulso y se repite al terminar.<br/><b>1 beat = 1 pulso.</b>', 225),
    (3, 'Pausar / reanudar', 'Toca un loop grabado para pausarlo o volver a reproducirlo.', 299),
    (4, 'Borrar', '<b>Todos:</b> mantén <b>Borrar beats 2 s</b>.<br/><b>Uno:</b> pausa el loop, mantenlo y toca <b>Borrar beats</b> con otro dedo.', 357),
    (5, 'Reset BPM', 'Mantén <b>Reset BPM 2 s</b>. Reinicia el tempo y SYNC; conserva los loops.', 430),
    (6, 'Flechas', '<b>&lt;&lt; retrasa · &gt;&gt; adelanta.</b><br/>1 toque = 1 ms; mantenido, repite. Sin sujetar un loop, ajusta todos y desactiva SYNC.', 492),
]
for index,(number,title,body,top) in enumerate(controls):
    end = callout(number,title,body,top,x=252,width=307,size=10,leading=13)
    limit = controls[index+1][3]-7 if index+1<len(controls) else 569
    assert end <= limit, (title,end,limit)

text('Tres gestos para recordar',35,578,15,bold=True)
text('Dos dedos: mantén 1 y toca 2 sin soltar.',35,599,9.2,MUTED)
cardtop=620;cardheight=143;cardwidth=(W-90)/3
for i,title in enumerate(['Dividir y restaurar','Ajustar un loop','Activar / quitar SYNC']):
    x=35+i*(cardwidth+10)
    rounded(x,cardtop,cardwidth,cardheight,PALE,BORDER,9)
    text(title,x+10,cardtop+10,10,ACCENT,True)
    if i==0:
        mini_button('4 beats',x+51,cardtop+31,65,38)
        badge(1,x+83,cardtop+70,7)
        arrow(x+32,cardtop+66,x+32,cardtop+31)
        arrow(x+136,cardtop+36,x+136,cardtop+71)
        text('÷2',x+12,cardtop+39,9,ACCENT,True)
        text('×2',x+137,cardtop+55,9,ACCENT,True)
        body='Con el loop sonando, desliza <b>fuera por arriba</b>: mitad. <b>Por abajo:</b> restaura hasta la duración original.'
    elif i==1:
        mini_button('4 beats',x+12,cardtop+31,64,38)
        mini_button('<<  >>',x+94,cardtop+31,58,38)
        badge(1,x+44,cardtop+70,7);badge(2,x+123,cardtop+70,7)
        arrow(x+80,cardtop+50,x+89,cardtop+50)
        body='Mantén el loop que suena y toca una flecha con otro dedo. Solo ajusta ese loop y <b>conserva SYNC</b>.'
    else:
        mini_button('4 beats',x+12,cardtop+31,64,38)
        mini_button('AUTO',x+94,cardtop+31,58,38)
        badge(1,x+44,cardtop+70,7);badge(2,x+123,cardtop+70,7)
        arrow(x+80,cardtop+50,x+89,cardtop+50)
        body='Con el loop <b>cuadrado y sonando</b>, mantén su botón y toca AUTO. Repite para quitar.<br/><b>SYNC…</b> espera; <b>SYNC</b> sigue el pulso.'
    height=paragraph(body,x+10,cardtop+81,cardwidth-20,8.9,10.5)
    assert height <= cardheight-87, (title,height)

note_height=paragraph('<b>SYNC</b> corrige pequeñas derivas sin cambiar el BPM. Dividir lo conserva; pausa, TAP o Reset BPM lo desactivan.<br/>'
          'Usa preferiblemente una <b>entrada directa de la música original</b> para evitar que vuelva a entrar el loop que está reproduciendo la app.',
          35,774,W-70,8.7,11,MUTED)
assert 774+note_height <= H-34, note_height
c.showPage()

header('Ajustes',2)
paragraph('Abre esta pantalla con la rueda dentada.',35,104,W-70,10,13,MUTED)
screen(35,136,234,True)
paragraph('Los valores de la imagen son ejemplos.',39,670,226,9,12,MUTED)
settings = [
    ('A','Desfase actual', 'Ajuste del loop mantenido o del último que has puesto a sonar. <b>Usar valor actual</b> lo guarda.',154),
    ('B','Desfase guardado', 'Se aplica a las próximas grabaciones. <b>Resetear valor</b> guarda 0 ms. No mueve los loops ya grabados.',252),
    ('C','Rango BPM Auto', 'Limita la búsqueda de AUTO. Mínimo y máximo separados al menos <b>20 BPM</b>.',350),
    ('D','Apariencia', 'Elige colores o texturas. Se aplica al momento; los LED mantienen sus colores.',440),
    ('E','Transparencia', '<b>0 %:</b> opaco. <b>100 %:</b> transparente. Texto y bordes siguen visibles.',530),
    ('F','Manual de instrucciones', 'Abre esta guía sin internet. Desliza para leer y pellizca para ampliar. Vuelve con <b>Volver</b> o <b>Atrás</b>.',625),
]
for index,(letter,title,body,top) in enumerate(settings):
    end=callout(letter,title,body,top,x=320,width=239,size=10.2,leading=14)
    limit=settings[index+1][3]-10 if index+1<len(settings) else 780
    assert end <= limit,(title,end,limit)

c.save()
print(OUTPUT)
print('Páginas: 2 · bloques de texto:',len(boxes))
