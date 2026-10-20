#include <QCoreApplication>
#include <QSvgRenderer>
#include <QImage>
#include <QPainter>
int main(int argc,char** argv){
    QCoreApplication app(argc,argv);
    if(argc!=3)return 2;
    QSvgRenderer svg(QString::fromLocal8Bit(argv[1]));
    if(!svg.isValid())return 3;
    QImage output(1024,1024,QImage::Format_ARGB32_Premultiplied);output.fill(Qt::transparent);
    QPainter painter(&output);painter.setRenderHint(QPainter::Antialiasing);svg.render(&painter);painter.end();
    return output.save(QString::fromLocal8Bit(argv[2]),"PNG")?0:4;
}
