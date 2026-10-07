#import <UIKit/UIKit.h>
#import <CoreBluetooth/CoreBluetooth.h>
#import <AVFoundation/AVFoundation.h>
#import <Security/Security.h>
#import <MediaPlayer/MediaPlayer.h>
#import <CallKit/CallKit.h>
#import "AstorGesturePolicy.h"
#import "AstorLunchGuide.h"
#import "AstorAssistReply.h"
#import "AstorWearGreeting.h"
#import "AstorDockArchive.h"
#import "AstorQuietDelivery.h"
#import "AstorReplyDrafts.h"
#import <UserNotifications/UserNotifications.h>
#import "AstorCallPolicy.h"
#import "AstorGlassesProbe-Swift.h"
#import <math.h>
#import <AIBuds/AIBuds.h>
#import <AIBudsLog/AIBudsLog-Swift.h>
#import <ABMate/ABMate-Swift.h>

@interface ProbeController : UIViewController <AIBudsSDKDelegate, CBCentralManagerDelegate, NSURLSessionTaskDelegate, AVAudioPlayerDelegate, AVSpeechSynthesizerDelegate, CXCallObserverDelegate>
@property(nonatomic,strong) CXCallObserver *callObserver;
@property(nonatomic,strong) UILabel *callLabel;
@property(nonatomic,assign) BOOL phoneCallActive, glassesCallActive, callActive, audioInterruptionActive;
@property(nonatomic,strong) UILabel *powerLabel;
@property(nonatomic,strong) AstorDockArchive *dock;
@property(nonatomic,assign) BOOL dockCharging;
@property(nonatomic,strong) NSMutableDictionary<NSString *,NSDictionary *> *powerStatus;
@property(nonatomic,strong) NSMutableSet<NSString *> *freshPowerComponents;
@property(nonatomic,strong) UITextView *report;
@property(nonatomic,strong) UIImageView *preview;
@property(nonatomic,strong) UIStackView *devices;
@property(nonatomic,strong) NSMutableArray<id<AIBudsFoundDeviceConvertible>> *found;
@property(nonatomic,strong) id<AIBudsDeviceConvertible> device;
@property(nonatomic,assign) NSUInteger frames, opusBytes, pcmBytes;
@property(nonatomic,strong) UILabel *metrics;
@property(nonatomic,strong) NSTimer *timer;
@property(nonatomic,assign) BOOL autoProbe, autoChecked;
@property(nonatomic,strong) NSString *logPath;
@property(nonatomic,assign) NSUInteger autoScanAttempts;
@property(nonatomic,strong) CBCentralManager *rawCentral;
@property(nonatomic,strong) NSMutableSet<NSUUID *> *rawFound;
@property(nonatomic,strong) UITextField *endpoint, *question;
@property(nonatomic,strong) AVAudioRecorder *recorder;
@property(nonatomic,strong) AVAudioPlayer *cue, *music, *answerAudio;
@property(nonatomic,strong) AVSpeechSynthesizer *speaker;
@property(nonatomic,strong) NSURL *recordingURL;
@property(nonatomic,strong) NSURLSessionDataTask *request;
@property(nonatomic,strong) NSURLSession *http;
@property(nonatomic,assign) BOOL waitingPhoto, busy;
@property(nonatomic,assign) NSUInteger photoGeneration, scanGeneration;
@property(nonatomic,assign) BOOL connecting, glassesVoiceEnabled, pocketMode, assigning, startingVoice, autoBound;
@property(nonatomic,strong) UILabel *connectionLabel, *statusLabel, *answerLabel, *gestureHint, *capabilityLabel, *voiceLabel;
@property(nonatomic,strong) UIButton *voiceButton, *pocketButton;
@property(nonatomic,strong) UISegmentedControl *tabs;
@property(nonatomic,strong) NSArray<UIStackView *> *pages;
@property(nonatomic,strong) UIStackView *gestureRows;
@property(nonatomic,strong) NSDictionary<NSNumber *,NSNumber *> *gestureMapping;
@property(nonatomic,strong) NSDictionary *gestureNames;
@property(nonatomic,strong) NSString *lastAnswer, *sessionToken;
@property(nonatomic,strong) NSArray<MPRemoteCommand *> *remoteCommands;
@property(nonatomic,strong) NSMutableArray *remoteTargets;
@property(nonatomic,assign) NSUInteger bindingGeneration;
@property(nonatomic,assign) UIBackgroundTaskIdentifier backgroundTask;
@property(nonatomic,strong) AstorLunchGuide *lunch;
@property(nonatomic,strong) UILabel *lunchStepLabel, *lunchHint;
@property(nonatomic,strong) UIButton *lunchBriefButton, *lunchNextButton, *lunchPhotoButton, *lunchStopButton;
@property(nonatomic,strong) NSDate *pendingSiriStart;
@property(nonatomic,strong) NSDictionary *photoContext;
@property(nonatomic,strong) NSDate *photoDeadline;
@property(nonatomic,strong) UILabel *lunchPhotoStatus;
@property(nonatomic,strong) UIButton *lunchPhotoRetry;
@property(nonatomic,strong) NSData *pendingPhotoImage;
@property(nonatomic,strong) NSDictionary *pendingPhotoContext;
@property(nonatomic,strong) NSString *pendingPhotoRequestId;
@property(nonatomic,strong) NSDate *pendingPhotoCreated;
@property(nonatomic,strong) AstorWearGreeting *wearGreeting;
@property(nonatomic,strong) UILabel *wearLabel;
@property(nonatomic,strong) UISwitch *wearGreetingSwitch;
@property(nonatomic,strong) NSString *wearDiagnosticDevice;
@property(nonatomic,assign) NSUInteger requestGeneration;
@property(nonatomic,strong) AstorQuietDelivery *messages;
@property(nonatomic,strong) UILabel *messageLabel;
@property(nonatomic,strong) UISwitch *messageSwitch;
@property(nonatomic,strong) AVAudioRecorder *silenceMonitor;
@property(nonatomic,strong) NSURL *silenceMonitorURL;
@property(nonatomic,strong) NSDate *messagePollAt;
@property(nonatomic,strong) AstorQuietDeliveryMessage *speakingMessage;
@property(nonatomic,assign) BOOL messagePolling, messageChannelMissingLogged, notificationsAllowed;
@property(nonatomic,strong) AstorReplyDrafts *drafts;
@property(nonatomic,strong) UILabel *draftLabel;
@property(nonatomic,strong) UIButton *draftSendButton, *draftSentButton, *draftDiscardButton;
@property(nonatomic,strong) AstorQuietDeliveryMessage *answeringMessage;
@property(nonatomic,strong) NSDate *replyDeadline;
@property(nonatomic,assign) BOOL replyRecording, messageHandedToOutput;
@property(nonatomic,assign) NSTimeInterval lastBriefCommand;
- (void)refreshGestures;
- (void)assignChanges:(NSDictionary *)changes restoring:(BOOL)restoring;
- (void)applySequence:(NSArray *)keys values:(NSDictionary *)values index:(NSUInteger)index generation:(NSUInteger)generation rollback:(NSDictionary *)rollback;


@end

@implementation ProbeController
- (UIColor *)ink {return [UIColor colorWithRed:.032 green:.039 blue:.051 alpha:1];}
- (UIColor *)cardColor {return [UIColor colorWithRed:.063 green:.078 blue:.102 alpha:1];}
- (UIColor *)silver {return [UIColor colorWithRed:.89 green:.91 blue:.93 alpha:1];}
- (UILabel *)label:(NSString *)text size:(CGFloat)size {
    UILabel *l=[UILabel new];l.text=text;l.numberOfLines=0;l.textColor=[self silver];
    l.font=[[UIFontMetrics metricsForTextStyle:UIFontTextStyleBody] scaledFontForFont:[UIFont systemFontOfSize:size weight:size>=20?UIFontWeightSemibold:UIFontWeightRegular]];
    l.adjustsFontForContentSizeCategory=YES;return l;
}
- (UIButton *)button:(NSString *)title action:(SEL)action {
    UIButton *b=[UIButton buttonWithType:UIButtonTypeSystem];[b setTitle:title forState:UIControlStateNormal];
    [b setTitleColor:[self silver] forState:UIControlStateNormal];b.backgroundColor=[self cardColor];
    b.titleLabel.font=[UIFont preferredFontForTextStyle:UIFontTextStyleHeadline];b.titleLabel.adjustsFontForContentSizeCategory=YES;
    b.titleLabel.numberOfLines=0;UIButtonConfiguration *config=UIButtonConfiguration.plainButtonConfiguration;config.contentInsets=NSDirectionalEdgeInsetsMake(14,14,14,14);config.title=title;config.titleTextAttributesTransformer=^NSDictionary *(NSDictionary *incoming){NSMutableDictionary *attributes=[incoming mutableCopy];attributes[NSFontAttributeName]=[UIFont preferredFontForTextStyle:UIFontTextStyleHeadline];return attributes;};config.baseForegroundColor=[self silver];b.configuration=config;
    b.layer.cornerRadius=16;[b.heightAnchor constraintGreaterThanOrEqualToConstant:52].active=YES;
    [b addTarget:self action:action forControlEvents:UIControlEventTouchUpInside];return b;
}
- (UIStackView *)card:(NSArray<UIView *> *)items {
    UIStackView *c=[[UIStackView alloc]initWithArrangedSubviews:items];c.axis=UILayoutConstraintAxisVertical;c.spacing=12;
    c.backgroundColor=[self cardColor];c.layer.cornerRadius=20;c.layoutMargins=UIEdgeInsetsMake(18,18,18,18);c.layoutMarginsRelativeArrangement=YES;return c;
}
- (UITextField *)field:(NSString *)placeholder {
    UITextField *f=[UITextField new];f.placeholder=placeholder;f.textColor=[self silver];f.font=[UIFont preferredFontForTextStyle:UIFontTextStyleBody];f.adjustsFontForContentSizeCategory=YES;
    f.backgroundColor=[UIColor colorWithWhite:.13 alpha:1];f.layer.cornerRadius=12;f.leftView=[[UIView alloc]initWithFrame:CGRectMake(0,0,12,1)];f.leftViewMode=UITextFieldViewModeAlways;
    f.attributedPlaceholder=[[NSAttributedString alloc]initWithString:placeholder attributes:@{NSForegroundColorAttributeName:[UIColor colorWithWhite:.65 alpha:1]}];
    [f.heightAnchor constraintGreaterThanOrEqualToConstant:50].active=YES;return f;
}
- (void)buildStaffUI {
    self.view.backgroundColor=[self ink];self.overrideUserInterfaceStyle=UIUserInterfaceStyleDark;
    self.connectionLabel=[self label:@"Очки не подключены" size:14];
    self.powerLabel=[self label:@"Очки · заряд ещё не получен\nКейс · заряд ещё не получен" size:13];
    UIStackView *header=[[UIStackView alloc]initWithArrangedSubviews:@[[self label:@"ASTOR  /  BUTLER" size:15],self.connectionLabel,self.powerLabel]];header.axis=UILayoutConstraintAxisVertical;header.spacing=8;
    UIScrollView *scroll=[UIScrollView new];scroll.keyboardDismissMode=UIScrollViewKeyboardDismissModeOnDrag;scroll.translatesAutoresizingMaskIntoConstraints=NO;header.translatesAutoresizingMaskIntoConstraints=NO;
    self.tabs=[[UISegmentedControl alloc]initWithItems:@[@"Смена",@"Очки",@"Активность"]];self.tabs.selectedSegmentIndex=0;self.tabs.selectedSegmentTintColor=[UIColor colorWithWhite:.28 alpha:1];self.tabs.translatesAutoresizingMaskIntoConstraints=NO;[self.tabs addTarget:self action:@selector(changeTab) forControlEvents:UIControlEventValueChanged];
    [self.view addSubview:header];[self.view addSubview:scroll];[self.view addSubview:self.tabs];
    [NSLayoutConstraint activateConstraints:@[[header.topAnchor constraintEqualToAnchor:self.view.safeAreaLayoutGuide.topAnchor constant:16],[header.leadingAnchor constraintEqualToAnchor:self.view.leadingAnchor constant:20],[header.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor constant:-20],
      [self.tabs.bottomAnchor constraintEqualToAnchor:self.view.safeAreaLayoutGuide.bottomAnchor constant:-10],[self.tabs.leadingAnchor constraintEqualToAnchor:self.view.leadingAnchor constant:20],[self.tabs.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor constant:-20],[self.tabs.heightAnchor constraintGreaterThanOrEqualToConstant:46],
      [scroll.topAnchor constraintEqualToAnchor:header.bottomAnchor constant:20],[scroll.leadingAnchor constraintEqualToAnchor:self.view.leadingAnchor],[scroll.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor],[scroll.bottomAnchor constraintEqualToAnchor:self.tabs.topAnchor constant:-14]]];
    UIStackView *body=[UIStackView new];body.axis=UILayoutConstraintAxisVertical;body.spacing=16;body.translatesAutoresizingMaskIntoConstraints=NO;[scroll addSubview:body];
    [NSLayoutConstraint activateConstraints:@[[body.topAnchor constraintEqualToAnchor:scroll.contentLayoutGuide.topAnchor],[body.bottomAnchor constraintEqualToAnchor:scroll.contentLayoutGuide.bottomAnchor constant:-16],[body.leadingAnchor constraintEqualToAnchor:scroll.contentLayoutGuide.leadingAnchor constant:20],[body.trailingAnchor constraintEqualToAnchor:scroll.contentLayoutGuide.trailingAnchor constant:-20],[body.widthAnchor constraintEqualToAnchor:scroll.frameLayoutGuide.widthAnchor constant:-40]]];
    NSMutableArray *pages=[NSMutableArray new];for(int i=0;i<3;i++){UIStackView *page=[UIStackView new];page.axis=UILayoutConstraintAxisVertical;page.spacing=16;page.hidden=i!=0;[pages addObject:page];[body addArrangedSubview:page];}self.pages=pages;
    UIStackView *shift=pages[0],*device=pages[1],*activity=pages[2];
    [shift addArrangedSubview:[self label:@"Ваш помощник\nна смене" size:30]];
    self.statusLabel=[self label:@"Подключите очки во вкладке «Очки»." size:16];
    self.callLabel=[self label:@"Звонок · два касания боковой панели — принять или завершить.\nНа время звонка подсказки приостановятся." size:14];[shift addArrangedSubview:self.callLabel];
    self.wearLabel=[self label:@"Приветствие при надевании · ждём данные датчика" size:14];
    self.wearGreetingSwitch=[UISwitch new];self.wearGreetingSwitch.on=[NSUserDefaults.standardUserDefaults boolForKey:@"AstorWearGreetingEnabled"];
    [self.wearGreetingSwitch addTarget:self action:@selector(wearGreetingChanged) forControlEvents:UIControlEventValueChanged];
    [shift addArrangedSubview:[self card:@[[self label:@"Приветствие при надевании" size:17],self.wearGreetingSwitch,self.wearLabel]]];
    self.lunchStepLabel=[self label:@"Бизнес-ланч на двоих" size:22];
    self.lunchHint=[self label:@"Четыре шага подачи: стол, приборы, вода и меню, проверка перед встречей." size:15];
    self.lunchBriefButton=[self button:@"Начать подачу" action:@selector(lunchBrief)];
    self.lunchNextButton=[self button:@"Следующий шаг" action:@selector(lunchNext)];
    self.lunchPhotoButton=[self button:@"Фото · проверить этот шаг" action:@selector(lunchPhoto)];
    self.lunchPhotoStatus=[self label:@"Фото по шагу" size:14];
    self.lunchPhotoRetry=[self button:@"Повторить отправку этого фото" action:@selector(retryLunchPhoto)];
    self.lunchStopButton=[self button:@"Завершить подачу" action:@selector(lunchStop)];
    [shift addArrangedSubview:[self card:@[[self label:@"ПОДАЧА · 2 ГОСТЯ" size:12],self.lunchStepLabel,self.lunchHint,self.lunchBriefButton,[self label:@"Фото приборов и финального вида сохраняются на сервере; следующий шаг выбираете вы." size:14],self.lunchPhotoStatus,self.lunchPhotoButton,self.lunchPhotoRetry,self.lunchNextButton,self.lunchStopButton]]];
    self.messageSwitch=[UISwitch new];self.messageSwitch.on=[NSUserDefaults.standardUserDefaults boolForKey:@"AstorQuietMessagesEnabled"];
    [self.messageSwitch addTarget:self action:@selector(messageDeliveryChanged) forControlEvents:UIControlEventValueChanged];
    self.messageLabel=[self label:@"Сообщений нет" size:14];
    [shift addArrangedSubview:[self card:@[[self label:@"СООБЩЕНИЯ СОТРУДНИКУ" size:12],self.messageSwitch,self.messageLabel,[self label:@"Сообщения читаются в очках только в тишине. Если вы говорите, сообщение ждёт и прозвучит через три секунды после разговора. Пока сообщение ждёт, микрофон измеряет только громкость: без распознавания, записи и отправки." size:14]]]];
    self.draftLabel=[self label:@"Черновиков ответа нет" size:14];
    self.draftSendButton=[self button:@"Отправить ответ в Telegram" action:@selector(sendDraft)];
    self.draftSentButton=[self button:@"Отправил · убрать черновик" action:@selector(confirmDraftSent)];
    self.draftDiscardButton=[self button:@"Стереть черновик" action:@selector(discardDraft)];
    [shift addArrangedSubview:[self card:@[[self label:@"ОТВЕТ ГОЛОСОМ" size:12],self.draftLabel,[self label:@"Пока экран заблокирован, Астор читает сообщение и записывает ваш ответ. Текст ждёт здесь: отправляете вы сами, из своего Telegram. Черновик исчезнет, только когда вы подтвердите отправку." size:14],self.draftSendButton,self.draftSentButton,self.draftDiscardButton]]];
    [self refreshLunch];
    [self refreshPower];
    [shift addArrangedSubview:[self card:@[[self label:@"ЗАДАЧИ ОТ BUTLER" size:12],[self label:@"Ждём подключение портала" size:21],[self label:@"Здесь появятся назначенные вам столы и этапы обслуживания. Сервер задач ещё не подключён." size:15]]]];
    self.voiceButton=[self button:@"Говорить с Butler" action:@selector(voice)];self.voiceButton.backgroundColor=[self silver];UIButtonConfiguration *voiceStyle=self.voiceButton.configuration;voiceStyle.baseForegroundColor=[self ink];self.voiceButton.configuration=voiceStyle;
    [shift addArrangedSubview:self.voiceButton];[shift addArrangedSubview:self.statusLabel];
    [shift addArrangedSubview:[self button:@"Фото · что я вижу?" action:@selector(takePhoto)]];
    [shift addArrangedSubview:[self card:@[[self label:@"Двойное нажатие в очках" size:17],[self label:@"Двойное нажатие начинает и завершает вопрос. Касание назад повторяет текущий шаг или последний ответ. Включите «Без экрана»." size:14]]]];
    self.pocketButton=[self button:@"Без экрана · включить тест" action:@selector(togglePocket)];[shift addArrangedSubview:self.pocketButton];
    if([NSFileManager.defaultManager fileExistsAtPath:[self musicURL].path])[shift addArrangedSubview:[self button:@"Ilkutki · слушать / пауза" action:@selector(playMusic)]];
    [shift addArrangedSubview:[self label:@"Фото этапа с привязкой к столу станет доступно после подключения API задач. Сейчас фото и вопросы идут в журнал смены." size:14]];
    self.question=[self field:@"Или напишите вопрос"];[shift addArrangedSubview:self.question];[shift addArrangedSubview:[self button:@"Отправить вопрос" action:@selector(ask)]];
    self.answerLabel=[self label:@"Ответ Butler появится здесь и прозвучит в очках." size:17];[shift addArrangedSubview:[self card:@[[self label:@"BUTLER" size:12],self.answerLabel,[self button:@"Повторить ответ" action:@selector(repeatAnswer)]]]];
    self.preview=[UIImageView new];self.preview.contentMode=UIViewContentModeScaleAspectFit;self.preview.layer.cornerRadius=16;self.preview.clipsToBounds=YES;self.preview.hidden=YES;[self.preview.heightAnchor constraintEqualToConstant:210].active=YES;[shift addArrangedSubview:self.preview];
    [shift addArrangedSubview:[self button:@"Остановить запись и звук" action:@selector(cancelAgent)]];
    [device addArrangedSubview:[self label:@"Ваши очки" size:30]];[device addArrangedSubview:[self button:@"Подключить AI Glasses" action:@selector(findGlasses)]];
    self.devices=[UIStackView new];self.devices.axis=UILayoutConstraintAxisVertical;self.devices.spacing=8;[device addArrangedSubview:self.devices];
    self.capabilityLabel=[self label:@"Модель 563B-E1769\nФото, микрофон и динамики проверены.\nПостоянный видеопоток эта прошивка не предоставляет." size:16];[device addArrangedSubview:[self card:@[self.capabilityLabel,[self button:@"Проверить динамики" action:@selector(testSpeaker)]]]];
    self.voiceLabel=[self label:@"Голос Butler" size:16];[device addArrangedSubview:[self card:@[self.voiceLabel,[self button:@"Выбрать голос" action:@selector(chooseRussianVoice)]]]];
    [device addArrangedSubview:[self card:@[[self label:@"Архив сессии" size:20],[self.dock makePanel]]]];
    [device addArrangedSubview:[self card:@[[self label:@"Голосовой старт" size:20],[self label:@"Скажите: «Привет, Siri. Астор». Если Siri не распознает имя, попробуйте «Запусти Астор». Откроется подача бизнес-ланча. iPhone может попросить разблокировку." size:15],[self button:@"Открыть Shortcuts" action:@selector(openShortcuts)]]]];
    [device addArrangedSubview:[self label:@"Жесты и кнопки" size:24]];self.gestureHint=[self label:@"Подключите очки: покажем только жесты, которые сообщает устройство." size:15];[device addArrangedSubview:self.gestureHint];
    [device addArrangedSubview:[self button:@"Настроить профиль Butler" action:@selector(applyButlerProfile)]];
    [device addArrangedSubview:[self button:@"Вернуть прежние назначения" action:@selector(restoreGestures)]];
    self.gestureRows=[UIStackView new];self.gestureRows.axis=UILayoutConstraintAxisVertical;self.gestureRows.spacing=8;[device addArrangedSubview:self.gestureRows];
    [device addArrangedSubview:[self button:@"События помощника SDK · вкл./выкл." action:@selector(toggleGlassesVoice)]];
    self.endpoint=[self field:@"HTTPS-адрес Astor"];self.endpoint.keyboardType=UIKeyboardTypeURL;self.endpoint.autocapitalizationType=UITextAutocapitalizationTypeNone;self.endpoint.autocorrectionType=UITextAutocorrectionTypeNo;self.endpoint.text=[NSUserDefaults.standardUserDefaults stringForKey:@"backendURL"];
    [device addArrangedSubview:[self card:@[[self label:@"Подключение к Astor" size:20],self.endpoint,[self button:@"Сохранить адрес" action:@selector(saveEndpoint)],[self button:@"Токен сотрудника" action:@selector(credentials)],[self label:@"Адрес и доступ выдаёт команда backend. Личные ключи ИИ сюда не нужны." size:14]]]];
    [activity addArrangedSubview:[self label:@"Активность" size:30]];[activity addArrangedSubview:[self label:@"Здесь пока журнал проверки устройства. История заданий и подтверждений появится с API портала." size:15]];
    self.metrics=[self label:@"" size:14];[activity addArrangedSubview:self.metrics];self.report=[UITextView new];self.report.editable=NO;self.report.backgroundColor=[self cardColor];self.report.textColor=[self silver];self.report.font=[UIFont monospacedSystemFontOfSize:12 weight:UIFontWeightRegular];self.report.layer.cornerRadius=16;[self.report.heightAnchor constraintEqualToConstant:360].active=YES;[activity addArrangedSubview:self.report];
    self.gestureNames=@{@1:@"Левая кнопка · одно нажатие",@2:@"Правая кнопка · одно нажатие",@3:@"Левая кнопка · двойное",@4:@"Правая кнопка · двойное",@5:@"Левая кнопка · тройное",@6:@"Правая кнопка · тройное",@7:@"Левая кнопка · удержание",@8:@"Правая кнопка · удержание",@16:@"Левая панель · касание",@17:@"Правая панель · касание",@18:@"Левая панель · двойное",@19:@"Правая панель · двойное",@20:@"Левая панель · удержание",@21:@"Правая панель · удержание",@22:@"Левая кнопка 1 · одно",@23:@"Правая кнопка 1 · одно",@24:@"Левая кнопка 1 · двойное",@25:@"Правая кнопка 1 · двойное",@26:@"Левая кнопка 1 · удержание",@27:@"Правая кнопка 1 · удержание",@28:@"Левая кнопка 2 · одно",@29:@"Правая кнопка 2 · одно",@30:@"Левая кнопка 2 · двойное",@31:@"Правая кнопка 2 · двойное",@32:@"Левая кнопка 2 · удержание",@33:@"Правая кнопка 2 · удержание",@34:@"Панель · свайп вперёд",@35:@"Панель · свайп назад"};
}
- (void)changeTab { [self.view endEditing:YES];for(NSUInteger i=0;i<self.pages.count;i++)self.pages[i].hidden=i!=self.tabs.selectedSegmentIndex; }
- (void)saveEndpoint { [NSUserDefaults.standardUserDefaults setObject:self.endpoint.text?:@"" forKey:@"backendURL"];[self.view endEditing:YES];[self log:@"Адрес сохранён. Готовность сервера проверяется реальным запросом."]; }
- (void)repeatAnswer {if(self.lastAnswer.length)[self speakAnswer:self.lastAnswer];else [self speakAnswer:@"Ответов Butler пока нет. Портал задач ещё не подключён."];}
- (BOOL)lunchIsBusy {return self.callActive || self.audioInterruptionActive || self.busy || self.waitingPhoto || self.recorder || self.startingVoice || self.music || self.dock.busy;}
- (void)refreshLunch {
    BOOL active=self.lunch.active;
    self.lunchStepLabel.text=active?[NSString stringWithFormat:@"%lu / %lu · %@",(unsigned long)self.lunch.stepIndex+1,(unsigned long)AstorLunchGuide.steps.count,self.lunch.step[@"title"]]:self.lunch.finished?@"Подача пройдена":@"Бизнес-ланч на двоих";
    self.lunchHint.text=active?self.lunch.step[@"hint"]:@"Четыре шага подачи: стол, приборы, вода и меню, проверка перед встречей.";
    UIButtonConfiguration *style=self.lunchBriefButton.configuration;style.title=active?@"Слушать шаг":self.lunch.finished?@"Начать заново":@"Начать подачу";self.lunchBriefButton.configuration=style;
    style=self.lunchNextButton.configuration;style.title=self.lunch.stepIndex+1==AstorLunchGuide.steps.count?@"Завершить подачу":@"Следующий шаг";self.lunchNextButton.configuration=style;
    self.lunchNextButton.hidden=!active;self.lunchPhotoButton.hidden=!active;self.lunchStopButton.hidden=!active && !self.lunch.finished;
    BOOL idle=![self lunchIsBusy];self.lunchBriefButton.enabled=idle;self.lunchNextButton.enabled=idle;self.lunchPhotoButton.enabled=idle;
    self.lunchNextButton.enabled=idle && self.lunch.canAdvance;
    self.lunchPhotoStatus.text=self.lunch.photoStatus;
    style=self.lunchPhotoButton.configuration;style.title=self.lunch.photoReceived?@"Переснять этот шаг":self.lunch.photoRequired?@"Снять обязательное фото шага":@"Фото · подсказка по шагу";self.lunchPhotoButton.configuration=style;
    self.lunchPhotoRetry.hidden=!self.pendingPhotoImage || ![self.lunch acceptsPhotoContext:self.pendingPhotoContext];
    self.lunchPhotoRetry.enabled=idle && -self.pendingPhotoCreated.timeIntervalSinceNow<110;
}
- (void)readLunchBrief {
    if([self lunchIsBusy]){[self log:@"Дождитесь записи или запроса перед подсказкой."];return;}
    [self.answerAudio stop];self.answerAudio=nil;[self.speaker stopSpeakingAtBoundary:AVSpeechBoundaryImmediate];
    NSString *brief=self.pocketMode?self.lunch.compactBrief:self.lunch.brief;
    self.lastAnswer=brief;self.answerLabel.text=brief;[self log:@"Шаг подачи озвучен и показан на экране."];[self speakAnswer:brief];
}
- (void)lunchBrief {if([self lunchIsBusy])return;if(!self.lunch.active){[self clearPendingPhoto];[self.lunch start];}[self refreshLunch];[self readLunchBrief];}
- (void)lunchNext {if([self lunchIsBusy])return;if(![self.lunch advance]){[self log:@"Сначала получите подтверждение сохранения фото этого шага."];return;}[self clearPendingPhoto];[self refreshLunch];[self readLunchBrief];}
- (void)lunchPhoto {NSDictionary *context=[self.lunch photoContext];if(context)[self beginPhotoForGuide:context];}
- (void)clearPendingPhoto {self.pendingPhotoImage=nil;self.pendingPhotoContext=nil;self.pendingPhotoRequestId=nil;self.pendingPhotoCreated=nil;}
- (void)retryLunchPhoto {
    if([self lunchIsBusy])return;
    if(!self.pendingPhotoImage || ![self.lunch acceptsPhotoContext:self.pendingPhotoContext] || -self.pendingPhotoCreated.timeIntervalSinceNow>=110){[self clearPendingPhoto];[self refreshLunch];[self log:@"Срок повтора истёк или шаг изменился. Сделайте новое фото."];return;}
    [self sendText:self.pendingPhotoContext[@"prompt"] image:self.pendingPhotoImage audio:nil photoContext:self.pendingPhotoContext requestId:self.pendingPhotoRequestId];
}
- (void)lunchStop {
    [self cancelAgent];[self.lunch stop];
    [self refreshLunch];self.lastAnswer=nil;self.answerLabel.text=@"Подача завершена.";[self log:@"Подача завершена. Голос и фото остановлены."];
}
- (void)remoteBrief {
    NSTimeInterval now=NSDate.timeIntervalSinceReferenceDate;if(now-self.lastBriefCommand<.8)return;self.lastBriefCommand=now;
    [self readLunchBrief];
}
- (void)log:(NSString *)message {
    NSLog(@"[AstorGlasses] %@", message);
    dispatch_async(dispatch_get_main_queue(), ^{
        self.report.text=[self.report.text stringByAppendingFormat:@"%@\n",message];
        if(self.report.text.length>24000)self.report.text=[self.report.text substringFromIndex:self.report.text.length-20000];
        if(![message hasPrefix:@"SDK"] && ![message hasPrefix:@"Жест "] && ![message hasPrefix:@"Вход:"] && ![message hasPrefix:@"Выход:"] && ![message hasPrefix:@"Прошивка:"] && ![message hasPrefix:@"LiveStreaming"] && ![message hasPrefix:@"Поддержка по SDK"] && ![message hasPrefix:@"Сохранено на очках"] && ![message hasPrefix:@"Счётчики"] && ![message hasPrefix:@"Модель очков сохранена"])self.statusLabel.text=message;
        NSFileHandle *file=[NSFileHandle fileHandleForWritingAtPath:self.logPath];
        [file seekToEndOfFile];
        [file writeData:[[message stringByAppendingString:@"\n"] dataUsingEncoding:NSUTF8StringEncoding]];
        [file closeFile];
    });
}
- (void)viewDidLoad {
    [super viewDidLoad]; self.view.backgroundColor=UIColor.systemBackgroundColor;
    self.lunch=[AstorLunchGuide new];
    self.messages=[AstorQuietDelivery new];
    self.drafts=[AstorReplyDrafts new];
    [NSUserDefaults.standardUserDefaults registerDefaults:@{@"AstorWearGreetingEnabled":@YES}];
    self.wearGreeting=[AstorWearGreeting new];id last=[NSUserDefaults.standardUserDefaults objectForKey:@"AstorWearGreetingAt"];
    self.dock=[AstorDockArchive shared];__weak typeof(self) dockWeak=self;
    self.dock.changed=^(NSString *status){[dockWeak log:status];};
    self.dock.prepareForImport=^{[dockWeak refreshDock];};
    if([last isKindOfClass:NSDate.class])self.wearGreeting.lastGreeting=last;
    self.autoProbe=[NSProcessInfo.processInfo.arguments containsObject:@"--astor-auto-probe"];
    self.glassesVoiceEnabled=[NSProcessInfo.processInfo.arguments containsObject:@"--astor-gesture-probe"];
    self.logPath=[NSSearchPathForDirectoriesInDomains(NSDocumentDirectory,NSUserDomainMask,YES).firstObject stringByAppendingPathComponent:@"probe.log"];
    [@"" writeToFile:self.logPath atomically:YES encoding:NSUTF8StringEncoding error:nil];
    NSString *voiceDirectory=[NSTemporaryDirectory() stringByAppendingPathComponent:@"AstorVoice"];
    [NSFileManager.defaultManager createDirectoryAtPath:voiceDirectory withIntermediateDirectories:YES attributes:@{NSFileProtectionKey:NSFileProtectionCompleteUntilFirstUserAuthentication} error:nil];
    [NSFileManager.defaultManager setAttributes:@{NSFileProtectionKey:NSFileProtectionCompleteUntilFirstUserAuthentication} ofItemAtPath:voiceDirectory error:nil];
    for(NSString *file in [NSFileManager.defaultManager contentsOfDirectoryAtPath:voiceDirectory error:nil])[NSFileManager.defaultManager removeItemAtPath:[voiceDirectory stringByAppendingPathComponent:file] error:nil];
    self.found=[NSMutableArray new];
    self.backgroundTask=UIBackgroundTaskInvalid;
    [self importPilotAccess];
    [NSUserDefaults.standardUserDefaults registerDefaults:@{@"AstorSpeechFamily":@"Milena"}];
    [self buildStaffUI];
    self.callObserver=[CXCallObserver new];[self.callObserver setDelegate:self queue:dispatch_get_main_queue()];[self syncPhoneCalls];
    [AstorSiriBridge refreshShortcuts];
    if([NSProcessInfo.processInfo.arguments containsObject:@"--astor-device-ui"]){self.tabs.selectedSegmentIndex=1;[self changeTab];}
    AVSpeechSynthesisVoice *voice=[self russianVoice];self.voiceLabel.text=voice?[NSString stringWithFormat:@"Голос Butler · %@%@",voice.name,voice.quality>AVSpeechSynthesisVoiceQualityDefault?@" · улучшенный":@""]:@"Русский голос пока недоступен";
    for(AVSpeechSynthesisVoice *v in AVSpeechSynthesisVoice.speechVoices)if([v.language.lowercaseString hasPrefix:@"ru"])[self log:[NSString stringWithFormat:@"Русский голос iPhone: %@, gender=%ld, quality=%ld.",v.name,(long)v.gender,(long)v.quality]];
    [self log:voice?[NSString stringWithFormat:@"Для Butler выбран голос: %@.",voice.name]:@"Для озвучки нужен русский голос iPhone."];
    AIBudsSDKConfiguration *configuration=[AIBudsSDKConfiguration defaultConfiguration];
    configuration.logLevel=AIBudsLogLevelMute;
    configuration.onlyDiscoverKnownDevices=NO;
    BOOL ok=[AIBudsSDK initWithBleSDKs:@[[ABMateSDK shared]] configuration:configuration delegate:self];
    [self log:ok?@"SDK готов. Отключите очки от AIBuds перед поиском.":@"SDK не инициализирован."];
    if(ok)[AIBudsStoredDevicesMgr loadDevicesInBackgroundWithCompletion:^(NSArray<id<AIBudsDeviceConvertible>> *saved,NSError *error){dispatch_async(dispatch_get_main_queue(),^{
        [self log:[NSString stringWithFormat:@"Сохранённых моделей очков: %lu.",(unsigned long)saved.count]];
        if(!error && saved.count==1){if(self.rawCentral.state==CBManagerStatePoweredOn)[self.rawCentral stopScan];self.scanGeneration++;[self connectModel:saved.firstObject];}
    });}];
    self.timer=[NSTimer scheduledTimerWithTimeInterval:1 target:self selector:@selector(refresh) userInfo:nil repeats:YES];
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(enteredBackground) name:UIApplicationDidEnterBackgroundNotification object:nil];
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(audioInterrupted:) name:AVAudioSessionInterruptionNotification object:nil];
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(routeChanged) name:AVAudioSessionRouteChangeNotification object:nil];
    if(ok && self.autoProbe) {
        [self log:@"Автотест: поиск, одно устройство, проверка возможностей; JPEG максимум 10 секунд."];
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW,3*NSEC_PER_SEC),dispatch_get_main_queue(),^{[self scan];});
    }
    if([NSProcessInfo.processInfo.arguments containsObject:@"--astor-raw-probe"]) {
        self.rawFound=[NSMutableSet new];
        self.rawCentral=[[CBCentralManager alloc]initWithDelegate:self queue:dispatch_get_main_queue()];
    }
}
- (void)centralManagerDidUpdateState:(CBCentralManager *)central {
    [self log:[NSString stringWithFormat:@"Прямой BLE: state=%ld",(long)central.state]];
    if(central.state!=CBManagerStatePoweredOn || self.connecting || self.device.isConnectedAndReady)return;
    NSUInteger generation=++self.scanGeneration;
    [central stopScan];
    [central scanForPeripheralsWithServices:nil options:@{CBCentralManagerScanOptionAllowDuplicatesKey:@YES}];
    [self log:@"Поиск AI Glasses: распознанные очки будут подключены."];
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW,90*NSEC_PER_SEC),dispatch_get_main_queue(),^{
        if(generation!=self.scanGeneration)return;
        [central stopScan];
        [self log:[NSString stringWithFormat:@"Прямой BLE-поиск завершён: подходящих устройств %lu.",(unsigned long)self.rawFound.count]];
    });
}
- (void)centralManager:(CBCentralManager *)central didDiscoverPeripheral:(CBPeripheral *)peripheral advertisementData:(NSDictionary<NSString *,id> *)advertisementData RSSI:(NSNumber *)RSSI {
    NSString *name=advertisementData[CBAdvertisementDataLocalNameKey]?:peripheral.name;
    NSArray<CBUUID *> *services=advertisementData[CBAdvertisementDataServiceUUIDsKey];
    BOOL serviceMatch=[services containsObject:[CBUUID UUIDWithString:@"FDB3"]];
    BOOL nameMatch=[name.localizedLowercaseString containsString:@"ai glasses"] || [name.localizedLowercaseString containsString:@"563b"];
    if(!nameMatch && !serviceMatch)return;
    if(![self.rawFound containsObject:peripheral.identifier]) {
        [self.rawFound addObject:peripheral.identifier];
        [self log:[NSString stringWithFormat:@"Прямой BLE: совпадение имени=%@, сервис FDB3=%@, RSSI=%@",nameMatch?@"да":@"нет",serviceMatch?@"да":@"нет",RSSI]];
    }
    if(self.connecting || self.device.isConnectedAndReady)return;
    if(nameMatch && serviceMatch) {
        BOOL known=[[ABMateSDK shared] isKnownDevice:peripheral advertisementData:advertisementData rssi:RSSI];
        id<AIBudsDeviceConvertible> model=[AIBudsSDK makeStorableDeviceFromDiscovered:peripheral central:central advertisement:advertisementData rssi:RSSI];
        [self log:[NSString stringWithFormat:@"Распознавание ABMate=%@, модель SDK=%@",known?@"да":@"нет",model?@"создана":@"не создана"]];
        if(model && !self.device.isConnectedAndReady) {
            [central stopScan]; self.scanGeneration++;
            id<AIBudsDeviceConvertible> stored=[AIBudsStoredDevicesMgr findDeviceByPeripheral:peripheral];
            if(!stored){ BOOL added=[AIBudsStoredDevicesMgr addDevice:model]; [self log:added?@"Модель добавлена в менеджер SDK.":@"Не удалось добавить модель в менеджер SDK."]; }
            self.device=stored?:model;
            [self connectModel:self.device];
        }
    }
}
- (void)refresh {
    // Debug-only, protected one-time credential import also works after launch.
    [self importPilotAccess];
    [self refreshSiriStart];
    [self refreshLunch];
    [self refreshPower];
    [self refreshWearGreeting];
    [self refreshMessages];
    [self refreshDock];
    self.metrics.text=[NSString stringWithFormat:@"Ready: %@ | кадров: %lu | Opus: %lu | PCM: %lu",
      self.device.isConnectedAndReady?@"да":@"нет",(unsigned long)self.frames,(unsigned long)self.opusBytes,(unsigned long)self.pcmBytes];
    self.connectionLabel.text=self.device.isConnectedAndReady?@"● Очки подключены":self.connecting?@"◌ Подключаем очки…":@"○ Очки не подключены";
    UIButtonConfiguration *voiceStyle=self.voiceButton.configuration;voiceStyle.title=self.recorder.isRecording?@"Закончить и отправить":self.startingVoice?@"Подключаем микрофон…":@"Говорить с Butler";self.voiceButton.configuration=voiceStyle;
    if(self.recorder.isRecording){self.statusLabel.text=[NSString stringWithFormat:@"● СЛУШАЮ · %.0f / 30 секунд\nДвойное нажатие завершит запись",self.recorder.currentTime];self.statusLabel.textColor=[UIColor colorWithRed:1 green:.52 blue:.45 alpha:1];}else self.statusLabel.textColor=[self silver];
    self.voiceButton.enabled=!self.callActive && !self.audioInterruptionActive && !self.waitingPhoto && !self.busy && !self.startingVoice;
    if(self.device.isConnectedAndReady && [self.device conformsToProtocol:@protocol(AIBudsDevicePhysicalOperationsAPI)]){
        NSDictionary *mapping=((id<AIBudsDevicePhysicalOperationsAPI>)self.device).physicalOperationsMapping;
        if(mapping.count && ![mapping isEqualToDictionary:self.gestureMapping]){self.gestureMapping=mapping;[self refreshGestures];}
    }
    AVSpeechSynthesisVoice *selected=[self russianVoice];self.voiceLabel.text=selected?[NSString stringWithFormat:@"Голос Butler · %@%@",selected.name,selected.quality>AVSpeechSynthesisVoiceQualityDefault?@" · улучшенный":@""]:@"Русский голос пока недоступен";
    if(self.device.isConnectedAndReady && [self.device conformsToProtocol:@protocol(AIBudsDeviceInfoAPI)]){
        id<AIBudsDeviceInfoAPI> info=(id<AIBudsDeviceInfoAPI>)self.device;
        self.capabilityLabel.text=[NSString stringWithFormat:@"AI Glasses · 563B-E1769\nПрошивка: %@\nФото по Bluetooth · проверено\nМикрофон и динамики · проверено\nНепрерывный видеопоток · недоступен",self.device.firmwareVersion?:@"ещё не получена"];
    }
    if(self.autoProbe && !self.autoChecked && self.device.isConnectedAndReady) {
        self.autoChecked=YES;self.connecting=NO;
        [AIBudsStoredDevicesMgr saveDevicesInBackgroundWithCompletion:^(NSError *error){[self log:error?@"Не удалось сохранить модель для повторного подключения.":@"Модель очков сохранена для повторного подключения."];}];
        [self log:@"Очки подключены. Можно проверить голос или снять фото."]; [self capabilities]; [self audioRoute];
        if([NSProcessInfo.processInfo.arguments containsObject:@"--astor-photo-probe"])[self takePhoto];
        if([NSProcessInfo.processInfo.arguments containsObject:@"--astor-music-probe"])dispatch_after(dispatch_time(DISPATCH_TIME_NOW,4*NSEC_PER_SEC),dispatch_get_main_queue(),^{[self playMusic];});
        if([NSProcessInfo.processInfo.arguments containsObject:@"--astor-voice-probe"])dispatch_after(dispatch_time(DISPATCH_TIME_NOW,4*NSEC_PER_SEC),dispatch_get_main_queue(),^{[self testSpeaker];});
        if([NSProcessInfo.processInfo.arguments containsObject:@"--astor-lunch-probe"])dispatch_after(dispatch_time(DISPATCH_TIME_NOW,4*NSEC_PER_SEC),dispatch_get_main_queue(),^{[self lunchBrief];});
        id<AIBudsLiveStreamingAPI> d=[self readyStream];
        if(d.supportsJPEGImageLiveStreaming && [NSProcessInfo.processInfo.arguments containsObject:@"--astor-auto-probe"]) {
            [self jpeg];
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW,10*NSEC_PER_SEC),dispatch_get_main_queue(),^{
                [self stop];
                [self log:[NSString stringWithFormat:@"Автотест JPEG завершён: кадров=%lu, Opus=%lu, PCM=%lu",(unsigned long)self.frames,(unsigned long)self.opusBytes,(unsigned long)self.pcmBytes]];
            });
        }
    }
}
- (void)messageDeliveryChanged {
    [NSUserDefaults.standardUserDefaults setBool:self.messageSwitch.on forKey:@"AstorQuietMessagesEnabled"];
    if(!self.messageSwitch.on){[self stopSilenceMonitor];[self.messages reset];[self log:@"Сообщения сотруднику выключены. Очередь очищена, микрофон не слушает."];}
    else {[self askForNotifications];[self log:@"Сообщения сотруднику включены: с открытым экраном приходят уведомлением, с заблокированным — голосом в очки."];}
    [self refreshMessages];
}
/* Measures loudness only, to know whether the staff member is talking. No recognition, no upload:
   the file is truncated by the recorder and deleted as soon as the queue is empty. */
- (void)startSilenceMonitor {
    if(self.silenceMonitor || self.recorder || self.startingVoice || self.callActive || self.audioInterruptionActive)return;
    if(![self hasGlassesOutput])return;
    NSString *directory=[NSTemporaryDirectory() stringByAppendingPathComponent:@"AstorVoice"];
    self.silenceMonitorURL=[NSURL fileURLWithPath:[directory stringByAppendingPathComponent:@"silence.m4a"]];
    NSDictionary *settings=@{AVFormatIDKey:@(kAudioFormatMPEG4AAC),AVSampleRateKey:@16000,AVNumberOfChannelsKey:@1,AVEncoderAudioQualityKey:@(AVAudioQualityLow)};
    NSError *error=nil;self.silenceMonitor=[[AVAudioRecorder alloc]initWithURL:self.silenceMonitorURL settings:settings error:&error];
    self.silenceMonitor.meteringEnabled=YES;
    if(!self.silenceMonitor || ![self.silenceMonitor record]){self.silenceMonitor=nil;[self log:@"Микрофон для паузы недоступен; сообщение прозвучит после текущего действия."];return;}
    [self log:@"Слушаю только громкость, чтобы не прервать разговор."];
}
- (void)stopSilenceMonitor {
    if(!self.silenceMonitor)return;
    [self.silenceMonitor stop];self.silenceMonitor=nil;
    if(self.silenceMonitorURL)[NSFileManager.defaultManager removeItemAtURL:self.silenceMonitorURL error:nil];
    self.silenceMonitorURL=nil;
}
- (BOOL)speechNearby {
    if(!self.silenceMonitor.isRecording)return NO;
    [self.silenceMonitor updateMeters];
    // −38 dBFS separates ordinary room noise from someone speaking a step away; verify on the device.
    return [self.silenceMonitor averagePowerForChannel:0]>-38;
}
/* With the app on screen the staff member is looking at the phone, so a message arrives as a notification
   and a line on screen; Astor does not talk over them. Locked or in the background, the glasses are the
   only way to reach them, so the message is read aloud in a pause. */
- (BOOL)screenInHands { return UIApplication.sharedApplication.applicationState==UIApplicationStateActive; }
- (void)refreshMessages {
    BOOL own=self.recorder!=nil || self.startingVoice || self.busy || self.waitingPhoto || self.speaker.isSpeaking || self.answerAudio.isPlaying || self.cue.isPlaying || self.dock.busy;
    [self refreshDrafts];
    if(!self.messageSwitch.on){self.messageLabel.text=@"Сообщения выключены";[self stopSilenceMonitor];return;}
    [self pollMessages];
    if([self screenInHands]){
        [self stopSilenceMonitor];
        [self showMessagesOnScreen];
        return;
    }
    if(self.messages.waiting && !own && !self.callActive && !self.audioInterruptionActive)[self startSilenceMonitor];else [self stopSilenceMonitor];
    AstorQuietDeliveryState state={0};
    state.speechNearby=[self speechNearby];
    state.ownAudioActive=own;
    state.callActive=self.callActive || self.audioInterruptionActive;
    state.musicActive=self.music.isPlaying;
    state.glassesReady=self.device.isConnectedAndReady && [self hasGlassesOutput];
    AstorQuietDeliveryMessage *next=[self.messages nextAt:NSDate.date state:state];
    self.messageLabel.text=self.messages.status;
    if(!next || self.speakingMessage)return;
    [self stopSilenceMonitor];
    self.speakingMessage=next;
    self.messageHandedToOutput=NO;
    [self.messages startedSpeaking:next at:NSDate.date];
    [self log:@"Читаю сообщение сотруднику в паузе разговора."];
    [self speakMessage:next.text];
    // The synthesizer reports completion; a message that never started playing returns to the queue.
    AstorQuietDeliveryMessage *spoken=next;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW,(int64_t)((2+next.text.length/12.)*NSEC_PER_SEC)),dispatch_get_main_queue(),^{
        if(self.speakingMessage!=spoken)return;
        BOOL delivered=self.messageHandedToOutput && !self.speaker.isSpeaking && !self.callActive && !self.audioInterruptionActive;
        self.speakingMessage=nil;
        [self.messages finishedSpeaking:spoken at:NSDate.date delivered:delivered];
        self.messageLabel.text=self.messages.status;
        [self log:delivered?@"Сообщение прочитано.":@"Сообщение прервано; прозвучит в следующую паузу."];
        if(delivered)[self inviteReplyTo:spoken];
    });
}
/* The message as a notification and a line on screen: once per message, and never aloud. */
- (void)showMessagesOnScreen {
    NSArray<AstorQuietDeliveryMessage *> *waiting=[self.messages pendingOnScreenAt:NSDate.date];
    self.messageLabel.text=self.messages.status;
    for(AstorQuietDeliveryMessage *message in waiting){
        [self.messages shownOnScreen:message at:NSDate.date];
        [self log:[NSString stringWithFormat:@"Сообщение на экране: %@",message.text]];
        [self notify:message];
    }
}
- (void)askForNotifications {
    UNUserNotificationCenter *center=UNUserNotificationCenter.currentNotificationCenter;
    [center requestAuthorizationWithOptions:UNAuthorizationOptionAlert|UNAuthorizationOptionSound
                          completionHandler:^(BOOL granted,NSError *error){dispatch_async(dispatch_get_main_queue(),^{
        self.notificationsAllowed=granted;
        if(!granted)[self log:@"Уведомления не разрешены: сообщения будут видны только на экране приложения."];
    });}];
}
- (void)notify:(AstorQuietDeliveryMessage *)message {
    if(!self.notificationsAllowed)return;
    UNMutableNotificationContent *content=[UNMutableNotificationContent new];
    content.title=@"Сообщение сотруднику";
    content.body=message.text;
    content.sound=UNNotificationSound.defaultSound;
    UNNotificationRequest *request=[UNNotificationRequest requestWithIdentifier:message.messageId content:content trigger:nil];
    [UNUserNotificationCenter.currentNotificationCenter addNotificationRequest:request withCompletionHandler:nil];
}
/* Astor's own voice when the server has one; otherwise the voice of the phone. */
/* The room can change while the server is synthesizing, so the decision to speak is taken again when
   the audio arrives — a message is never dropped into a conversation that started in the meantime. */
- (BOOL)canSpeakMessageNow {
    return !self.callActive && !self.audioInterruptionActive && !self.recorder && !self.startingVoice
            && ![self screenInHands] && ![self speechNearby] && self.device.isConnectedAndReady && [self hasGlassesOutput];
}
- (void)abandonSpokenMessage:(NSString *)reason {
    AstorQuietDeliveryMessage *message=self.speakingMessage;
    if(!message)return;
    self.speakingMessage=nil;
    self.messageHandedToOutput=NO;
    [self.messages finishedSpeaking:message at:NSDate.date delivered:NO];
    self.messageLabel.text=self.messages.status;
    [self log:reason];
}
- (void)speakMessage:(NSString *)text {
    NSURL *url=[self speechEndpoint];NSString *token=[self token];
    if(!url || !token.length){self.messageHandedToOutput=YES;[self speakAnswer:text];return;}
    NSString *requestId=NSUUID.UUID.UUIDString.lowercaseString;
    NSMutableURLRequest *request=[NSMutableURLRequest requestWithURL:url];request.HTTPMethod=@"POST";request.timeoutInterval=12;
    [request setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
    [request setValue:[@"Bearer " stringByAppendingString:token] forHTTPHeaderField:@"Authorization"];
    request.HTTPBody=[NSJSONSerialization dataWithJSONObject:@{@"requestId":requestId,@"text":text} options:0 error:nil];
    NSURLSessionConfiguration *config=NSURLSessionConfiguration.ephemeralSessionConfiguration;config.URLCache=nil;
    NSURLSession *session=[NSURLSession sessionWithConfiguration:config delegate:(id<NSURLSessionDelegate>)self delegateQueue:nil];
    [[session dataTaskWithRequest:request completionHandler:^(NSData *data,NSURLResponse *response,NSError *error){dispatch_async(dispatch_get_main_queue(),^{
        [session finishTasksAndInvalidate];
        NSInteger status=((NSHTTPURLResponse *)response).statusCode;
        NSDictionary *reply=(!error && status==200 && data.length<=3*1024*1024)?[NSJSONSerialization JSONObjectWithData:data options:0 error:nil]:nil;
        if(![self canSpeakMessageNow]){[self abandonSpokenMessage:@"Пока готовился голос, началось другое действие: сообщение прозвучит в следующую паузу."];return;}
        NSString *encoded=[reply isKindOfClass:NSDictionary.class]?reply[@"audioBase64"]:nil;
        if([encoded isKindOfClass:NSString.class] && encoded.length && [reply[@"audioMimeType"] isEqual:@"audio/mpeg"]){
            NSData *speech=[[NSData alloc]initWithBase64EncodedString:encoded options:0];
            if(speech.length && speech.length<=2*1024*1024){self.messageHandedToOutput=YES;[self playBackendSpeech:speech];return;}
        }
        self.messageHandedToOutput=YES;
        [self speakAnswer:text];
    });}] resume];
}
- (NSURL *)speechEndpoint {
    NSURL *assist=[self validEndpoint:NO];
    if(!assist)return nil;
    NSURLComponents *components=[NSURLComponents componentsWithURL:assist resolvingAgainstBaseURL:NO];
    if(![components.path hasSuffix:@"/assist"])return nil;
    components.path=[[components.path substringToIndex:components.path.length-@"assist".length] stringByAppendingString:@"speech"];
    return components.URL;
}
/* After a message was read aloud, the staff member may answer with their own voice. The recording is
   bounded and explicit, and the text becomes a draft they send themselves: nothing leaves on its own. */
- (void)inviteReplyTo:(AstorQuietDeliveryMessage *)message {
    if([self screenInHands] || self.callActive || self.audioInterruptionActive || self.recorder || self.busy)return;
    if(!self.device.isConnectedAndReady || ![self hasGlassesOutput])return;
    self.answeringMessage=message;
    self.replyDeadline=[NSDate dateWithTimeIntervalSinceNow:25];
    [self log:@"Предлагаю ответить голосом: двойное нажатие начнёт запись ответа."];
    [self speakAnswer:@"Чтобы ответить, нажмите дважды и скажите ответ."];
}
- (BOOL)replyWanted { return self.answeringMessage!=nil && self.replyDeadline.timeIntervalSinceNow>0; }
- (void)refreshDrafts {
    NSUInteger count=[self.drafts countAt:NSDate.date];
    AstorReplyDraft *first=[self.drafts firstAt:NSDate.date];
    self.draftLabel.text=[self.drafts statusAt:NSDate.date];
    self.draftSendButton.hidden=count==0;
    self.draftDiscardButton.hidden=count==0;
    self.draftSentButton.hidden=count==0 || !first.handedOverAt;
    UIButtonConfiguration *style=self.draftSendButton.configuration;
    style.title=first.handedOverAt?@"Открыть в Telegram снова":@"Отправить ответ в Telegram";
    self.draftSendButton.configuration=style;
}
- (void)sendDraft {
    AstorReplyDraft *draft=[self.drafts firstAt:NSDate.date];
    if(!draft){[self log:@"Черновика нет."];return;}
    NSURL *url=[AstorReplyDrafts telegramShareURLFor:draft];
    if(!url){[self log:@"Не удалось подготовить текст для Telegram."];return;}
    [UIApplication.sharedApplication openURL:url options:@{} completionHandler:^(BOOL success){dispatch_async(dispatch_get_main_queue(),^{
        if(!success){[self log:@"Telegram не открылся. Текст можно скопировать с экрана."];return;}
        // Telegram is open with the text prefilled. The draft stays until the staff member says they sent it:
        // they may still change their mind in Telegram, and a vanished draft would lose the answer.
        [self.drafts markHandedOver:draft.draftId at:NSDate.date];
        [self refreshDrafts];
        [self log:@"Текст открыт в Telegram. Черновик останется здесь, пока вы не подтвердите отправку."];
    });}];
}
- (void)confirmDraftSent {
    AstorReplyDraft *draft=[self.drafts firstAt:NSDate.date];
    if(draft && [self.drafts markSent:draft.draftId])[self log:@"Отправка подтверждена; черновик убран."];
    [self refreshDrafts];
}
- (void)discardDraft {
    AstorReplyDraft *draft=[self.drafts firstAt:NSDate.date];
    if(draft && [self.drafts discard:draft.draftId])[self log:@"Черновик стёрт."];
    [self refreshDrafts];
}
/* Pulls messages addressed to this server-bound scope. Informational: nothing is acknowledged and
   nothing is sent back, so the restaurant still sees an unanswered message as unanswered. */
- (void)pollMessages {
    if(self.messagePolling || self.messages.waiting>=10)return;
    if(self.messagePollAt && -self.messagePollAt.timeIntervalSinceNow<20)return;
    NSURL *assist=[self validEndpoint:NO];NSString *token=[self token];
    if(!assist || !token.length)return;
    // The assist URL ends with /api/glasses/assist; messages live beside it, built from the same components.
    NSURLComponents *components=[NSURLComponents componentsWithURL:assist resolvingAgainstBaseURL:NO];
    if(![components.path hasSuffix:@"/assist"])return;
    components.path=[[components.path substringToIndex:components.path.length-@"assist".length] stringByAppendingString:@"messages"];
    NSURL *url=components.URL;
    if(!url)return;
    self.messagePollAt=NSDate.date;self.messagePolling=YES;
    NSMutableURLRequest *request=[NSMutableURLRequest requestWithURL:url];request.timeoutInterval=15;
    [request setValue:[@"Bearer " stringByAppendingString:token] forHTTPHeaderField:@"Authorization"];
    NSURLSessionConfiguration *config=NSURLSessionConfiguration.ephemeralSessionConfiguration;config.URLCache=nil;
    NSURLSession *session=[NSURLSession sessionWithConfiguration:config delegate:(id<NSURLSessionDelegate>)self delegateQueue:nil];
    [[session dataTaskWithRequest:request completionHandler:^(NSData *data,NSURLResponse *response,NSError *error){dispatch_async(dispatch_get_main_queue(),^{
        [session finishTasksAndInvalidate];self.messagePolling=NO;
        NSInteger status=((NSHTTPURLResponse *)response).statusCode;
        if(error || status!=200){
            if(status==404 && !self.messageChannelMissingLogged){self.messageChannelMissingLogged=YES;[self log:@"Канал сообщений на сервере не включён; очередь остаётся пустой."];}
            return;
        }
        NSDictionary *reply=data.length<=256*1024?[NSJSONSerialization JSONObjectWithData:data options:0 error:nil]:nil;
        NSArray *items=[reply isKindOfClass:NSDictionary.class]?reply[@"messages"]:nil;
        if(![items isKindOfClass:NSArray.class] || items.count>20)return;
        NSUInteger added=0;
        for(id item in items){
            if(![item isKindOfClass:NSDictionary.class])continue;
            AstorQuietDeliveryMessage *message=[AstorQuietDeliveryMessage withId:item[@"id"] text:item[@"text"] at:NSDate.date];
            if([self.messages enqueue:message])added++;
        }
        if(added){[self log:[NSString stringWithFormat:@"Новых сообщений сотруднику: %lu. Прозвучат в паузе.",(unsigned long)added]];self.messageLabel.text=self.messages.status;}
    });}] resume];
}
- (void)refreshSiriStart {
    id requested=[NSUserDefaults.standardUserDefaults objectForKey:@"AstorSiriStartRequestedAt"];
    if(requested){[NSUserDefaults.standardUserDefaults removeObjectForKey:@"AstorSiriStartRequestedAt"];
        if([requested isKindOfClass:NSDate.class] && -[requested timeIntervalSinceNow]>=0 && -[requested timeIntervalSinceNow]<45){
            self.pendingSiriStart=requested;[self log:@"Голосовой старт получен. Ожидаю подключение очков и завершение Siri."];
        }
    }
    if(!self.pendingSiriStart)return;
    if(-self.pendingSiriStart.timeIntervalSinceNow>45){self.pendingSiriStart=nil;self.statusLabel.text=@"Подключите очки и повторите «Сири, Астор».";[self log:@"Голосовой старт истёк: очки или аудиосессия не готовы."];return;}
    if(-self.pendingSiriStart.timeIntervalSinceNow<2 || UIApplication.sharedApplication.applicationState!=UIApplicationStateActive || self.callActive || self.audioInterruptionActive || !self.device.isConnectedAndReady || ![self hasGlassesOutput])return;
    self.pendingSiriStart=nil;[self cancelAgent];self.tabs.selectedSegmentIndex=0;[self changeTab];[self lunchBrief];
}
- (void)wearGreetingChanged {
    [NSUserDefaults.standardUserDefaults setBool:self.wearGreetingSwitch.on forKey:@"AstorWearGreetingEnabled"];
    if(!self.wearGreetingSwitch.on)[self.wearGreeting observeStatus:-1 at:NSDate.date];
    if(self.wearGreetingSwitch.on && self.device.isConnectedAndReady && [self.device conformsToProtocol:@protocol(AIBudsDeviceWearDetectionAPI)]){
        id<AIBudsDeviceWearDetectionAPI> wear=(id<AIBudsDeviceWearDetectionAPI>)self.device;
        if(wear.wearDetectionCapability==AIBudsWearDetectionCapabilitySupportedAndConfigurable && !wear.isWearDetectionEnabled)
            [wear setWearDetectionEnabled:YES completion:^(BOOL success,NSError *error){dispatch_async(dispatch_get_main_queue(),^{[self log:success?@"Датчик надевания включён на очках.":@"Очки не подтвердили включение датчика надевания."];[self refreshWearGreeting];});}];
    }
    [self refreshWearGreeting];
}
- (void)refreshWearGreeting {
    if(!self.device.isConnectedAndReady){self.wearLabel.text=@"Датчик надевания · очки не подключены";[self.wearGreeting observeStatus:-1 at:NSDate.date];self.wearDiagnosticDevice=nil;return;}
    BOOL protocol=[self.device conformsToProtocol:@protocol(AIBudsDeviceWearDetectionAPI)];
    id<AIBudsDeviceWearDetectionAPI> wear=protocol?(id<AIBudsDeviceWearDetectionAPI>)self.device:nil;
    NSInteger capability=wear?wear.wearDetectionCapability:0;
    NSString *diagnostic=[NSString stringWithFormat:@"%@:%ld:%d",self.device.uuid.UUIDString,(long)capability,wear.isWearDetectionEnabled];
    if(![self.wearDiagnosticDevice isEqual:diagnostic]){self.wearDiagnosticDevice=diagnostic;[self log:[NSString stringWithFormat:@"SDK датчик надевания: capability=%ld, enabled=%@.",(long)capability,wear.isWearDetectionEnabled?@"да":@"нет"]];}
    if(!protocol || capability==AIBudsWearDetectionCapabilityNone){self.wearLabel.text=@"Эта модель не сообщает датчик надевания";[self.wearGreeting observeStatus:-1 at:NSDate.date];return;}
    if(!wear.isWearDetectionEnabled){self.wearLabel.text=@"Датчик поддерживается, но выключен на очках";[self.wearGreeting observeStatus:-1 at:NSDate.date];return;}
    self.wearLabel.text=[NSString stringWithFormat:@"Датчик включён · состояние %ld. Автоприветствие %@.",(long)wear.wearStatus,self.wearGreetingSwitch.on?@"включено":@"выключено"];
    BOOL allowed=self.wearGreetingSwitch.on && [self hasGlassesOutput] && ![self lunchIsBusy] && !self.speaker.isSpeaking && !self.answerAudio.isPlaying && !self.cue.isPlaying;
    if([self.wearGreeting consumeAt:NSDate.date allowed:allowed]){
        [NSUserDefaults.standardUserDefaults setObject:self.wearGreeting.lastGreeting forKey:@"AstorWearGreetingAt"];
        [self log:@"Приветствие по событию надевания."];[self speakAnswer:@"Привет! Я Астор. Готов помочь на смене."];
    }
}
- (void)device:(id<AIBudsDeviceConvertible>)device didWearStatusChanged:(enum AIBudsWearStatus)status {
    dispatch_async(dispatch_get_main_queue(),^{
        if(device!=self.device || ![device conformsToProtocol:@protocol(AIBudsDeviceWearDetectionAPI)])return;
        id<AIBudsDeviceWearDetectionAPI> wear=(id<AIBudsDeviceWearDetectionAPI>)device;
        if(!wear.isWearDetectionEnabled || wear.wearDetectionCapability==AIBudsWearDetectionCapabilityNone || !self.wearGreetingSwitch.on)return;
        [self log:[NSString stringWithFormat:@"SDK событие надевания: %ld.",(long)status]];
        [self.wearGreeting observeStatus:status at:NSDate.date];[self refreshWearGreeting];
    });
}
- (void)scan {
    self.autoScanAttempts++;
    [AIBudsSDK stopScanning]; [self.found removeAllObjects];
    for(UIView *v in self.devices.arrangedSubviews.copy) { [self.devices removeArrangedSubview:v]; [v removeFromSuperview]; }
    __weak typeof(self) weak=self;
    [self log:[NSString stringWithFormat:@"Поиск начат. Bluetooth authorization=%ld",(long)CBCentralManager.authorization]];
    [AIBudsSDK startScanningWithTimeout:self.autoProbe?@60:@20 deviceFoundHandler:^(id<AIBudsFoundDeviceConvertible> found, BOOL existing) {
        dispatch_async(dispatch_get_main_queue(), ^{
            typeof(self) s=weak; if(!s)return;
            NSString *name=found.peripheral.name?:found.advertisementData[CBAdvertisementDataLocalNameKey];
            if(![name.localizedLowercaseString containsString:@"ai glasses"] && ![name.localizedLowercaseString containsString:@"563b"])return;
            for(id<AIBudsFoundDeviceConvertible> item in s.found) if([item.peripheral.identifier isEqual:found.peripheral.identifier])return;
            if(s.found.count>=4)return;
            UIButton *b=[s button:name action:@selector(connect:)]; b.tag=s.found.count;
            [s.found addObject:found]; [s.devices addArrangedSubview:b];
            [s log:@"Найдены очки с подходящим именем."];
        });
    } completion:^{ dispatch_async(dispatch_get_main_queue(),^{
        [weak log:[NSString stringWithFormat:@"Поиск завершён: найдено очков %lu.",(unsigned long)weak.found.count]];
        if(weak.autoProbe && weak.found.count==1)[weak connectIndex:0];
        else if(weak.autoProbe && weak.found.count==0 && weak.autoScanAttempts<3) {
            [weak log:@"Повторяю поиск: очки пока не обнаружены."];
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW,NSEC_PER_SEC),dispatch_get_main_queue(),^{[weak scan];});
        } else if(weak.autoProbe)[weak log:@"Автоподключение остановлено: нужен ровно один кандидат."];
    }); }];
}
- (void)connect:(UIButton *)button {
    [self connectIndex:button.tag];
}
- (void)connectIndex:(NSUInteger)index {
    [self stop]; [AIBudsSDK stopScanning];
    if(index>=self.found.count)return;
    [self.device disconnect];
    self.device=[AIBudsSDK makeStorableDeviceFromDiscovered:self.found[index]];
    if(!self.device){[self log:@"SDK не распознал устройство."];return;}
    BOOL added=[AIBudsStoredDevicesMgr addDevice:self.device];
    if(!added){ id<AIBudsDeviceConvertible> stored=[AIBudsStoredDevicesMgr findDeviceByPeripheral:self.found[index].peripheral]; if(stored)self.device=stored; }
    AIBudsConnectParams *params=[AIBudsConnectParams new]; params.userId=@"astor-local-probe";
    [self.device connectWithParams:params];
    [self log:@"Подключение… Дождитесь Ready: да, затем проверьте возможности."];
    if(self.autoProbe)dispatch_after(dispatch_time(DISPATCH_TIME_NOW,30*NSEC_PER_SEC),dispatch_get_main_queue(),^{
        if(!self.device.isConnectedAndReady){[self log:@"Автотест: Ready не достигнут за 30 секунд."]; [self.device disconnect];}
    });
}
- (id<AIBudsLiveStreamingAPI>)readyStream {
    if(self.dock.busy){[self log:@"Дождитесь импорта записей с очков."];return nil;}
    if(!self.device.isConnectedAndReady){[self log:@"Очки ещё не подключены и не готовы."];return nil;}
    if(![self.device conformsToProtocol:@protocol(AIBudsLiveStreamingAPI)]){[self log:@"SDK не предоставляет LiveStreamingAPI для этих очков."];return nil;}
    return (id<AIBudsLiveStreamingAPI>)self.device;
}
- (void)audioRoute {
    AVAudioSession *session=AVAudioSession.sharedInstance;
    NSError *error=nil;
    if(![session setCategory:AVAudioSessionCategoryPlayAndRecord mode:AVAudioSessionModeDefault options:AVAudioSessionCategoryOptionAllowBluetooth|AVAudioSessionCategoryOptionAllowBluetoothA2DP error:&error] || ![session setActive:YES error:&error]) {
        [self log:@"Не удалось активировать аудиосессию."];return;
    }
    AVAudioSessionRouteDescription *route=session.currentRoute;
    for(AVAudioSessionPortDescription *port in route.inputs) [self log:[NSString stringWithFormat:@"Вход: %@ (%@)",port.portName,port.portType]];
    for(AVAudioSessionPortDescription *port in route.outputs) [self log:[NSString stringWithFormat:@"Выход: %@ (%@)",port.portName,port.portType]];

}
- (void)capabilities {
    if(self.device.isConnectedAndReady && [self.device conformsToProtocol:@protocol(AIBudsDeviceFirmwareAPI)]) {
        NSString *version=((id<AIBudsDeviceFirmwareAPI>)self.device).firmwareVersion;
        if(version)[self log:[NSString stringWithFormat:@"Прошивка: %@",version]];
    }
    if(self.device.isConnectedAndReady && [self.device conformsToProtocol:@protocol(AIBudsDeviceInfoAPI)]) {
        id<AIBudsDeviceInfoAPI> info=(id<AIBudsDeviceInfoAPI>)self.device;
        if(info.deviceCapabilities)[self log:[NSString stringWithFormat:@"LiveStreaming capability=%@",info.deviceCapabilities.supportsLiveStreaming?@"да":@"нет"]];
        else [self log:@"Общие capabilities пока не получены."];
        AIBudsMediaCountInfoModel *media=info.mediaCountInfo;
        if(media)[self log:[NSString stringWithFormat:@"Сохранено на очках: фото=%@, видео=%@, аудио=%@",media.photoCount,media.videoCount,media.audioCount]];
        else [self log:@"Счётчики сохранённых файлов пока не получены."];
        [self log:[NSString stringWithFormat:@"SDK API импорта файлов=%@ (сам импорт не проверен)",[self.device conformsToProtocol:@protocol(AIBudsDeviceMediaFileImportAPI)]?@"есть":@"нет"]];
    }
    if([self.device conformsToProtocol:@protocol(AIBudsDevicePhysicalOperationsAPI)])[self device:self.device didPhysicalOperationsMappingChanged:((id<AIBudsDevicePhysicalOperationsAPI>)self.device).physicalOperationsMapping];
    id<AIBudsLiveStreamingAPI> d=[self readyStream]; if(!d)return;
    [self log:[NSString stringWithFormat:@"Поддержка по SDK: RTSP=%@, JPEG=%@. Реальный поток требует отдельного теста.",
      d.supportsRTSPLiveStreaming?@"да":@"нет",d.supportsJPEGImageLiveStreaming?@"да":@"нет"]];
}
- (void)device:(id<AIBudsDeviceConvertible>)device didPhysicalOperationsMappingChanged:(NSDictionary<NSNumber *,NSNumber *> *)mapping {
    dispatch_async(dispatch_get_main_queue(),^{if(device!=self.device)return;self.gestureMapping=mapping;[self refreshGestures];
        [self log:[NSString stringWithFormat:@"SDK сообщает назначений жестов: %lu.",(unsigned long)mapping.count]];
        if(mapping.count && !self.autoBound && [NSProcessInfo.processInfo.arguments containsObject:@"--astor-bind-profile"]){self.autoBound=YES;dispatch_after(dispatch_time(DISPATCH_TIME_NOW,NSEC_PER_SEC),dispatch_get_main_queue(),^{[self applyButlerProfile];});}
        if([NSProcessInfo.processInfo.arguments containsObject:@"--astor-pocket-probe"] && !self.pocketMode)dispatch_after(dispatch_time(DISPATCH_TIME_NOW,2*NSEC_PER_SEC),dispatch_get_main_queue(),^{if(!self.pocketMode)[self togglePocket];});
        for(NSNumber *op in [[mapping allKeys] sortedArrayUsingSelector:@selector(compare:)])[self log:[NSString stringWithFormat:@"Жест %@ → функция %@.",op,mapping[op]]];
    });
}
- (void)device:(id<AIBudsDeviceConvertible>)device didClassicBtConnectionStatusChanged:(BOOL)isConnected {
    [self log:isConnected?@"Classic Bluetooth подключён.":@"Classic Bluetooth отключён."];
}
- (void)jpeg {
    id<AIBudsLiveStreamingAPI> d=[self readyStream]; if(!d)return;
    if(!d.supportsJPEGImageLiveStreaming){[self log:@"JPEG-поток не поддерживается по SDK."];return;}
    [self stop]; self.frames=0; self.opusBytes=0; self.pcmBytes=0;
    __weak typeof(self) weak=self;
    [d startJPEGImageLiveStreamingWithSessionStartCompletionHandler:^(BOOL success, NSError *error){
        [weak log:success?@"JPEG-сессия запущена. Ждём первый кадр.":@"Запуск JPEG завершился ошибкой."];
    } jpegDataReceivedHandler:^(NSData *data){
        UIImage *frame=[UIImage imageWithData:data];
        dispatch_async(dispatch_get_main_queue(), ^{ if(frame){
            weak.frames++; weak.preview.image=frame;
            if(weak.frames==1)[weak log:@"Получен первый JPEG-кадр."];
        } });
    } opusDataReceivedHandler:^(NSData *opus, NSData *pcm){
        dispatch_async(dispatch_get_main_queue(), ^{weak.opusBytes+=opus.length; weak.pcmBytes+=pcm.length;});
    } sessionFinishHandler:^(BOOL userStop, NSError *error){[weak log:@"JPEG-сессия завершена."]; }];
}
- (void)rtsp {
    id<AIBudsLiveStreamingAPI> d=[self readyStream]; if(!d)return;
    if(!d.supportsRTSPLiveStreaming){[self log:@"RTSP не поддерживается по SDK."];return;}
    [self stop]; __weak typeof(self) weak=self;
    [d startRTSPLiveStreamingWithParams:[AIBudsRTSPStreamParams defaultParams]
      configureHotspotStartingHandler:^{[weak log:@"Настройка Wi-Fi очков…"];}
      hotspotConfigureCompletionHandler:nil enterLiveStreamingModeStartingHandler:nil
      enterLiveStreamingModeCompletedHandler:nil waitingForHotspotOpenHandler:nil
      connectDeviceHotspotStartingHandler:nil deviceHotspotConnectCompletionHandler:nil
      rtspAddressReceivedHandler:^(NSString *address){[weak log:@"RTSP-адрес получен. Декодирование видео ещё не добавлено."];}
      sessionStartCompletionHandler:^(BOOL success,NSError *error){[weak log:success?@"RTSP-сессия запущена.":@"Ошибка запуска RTSP."];}
      sessionFinishHandler:^(BOOL userStop,NSError *error){[weak log:@"RTSP-сессия завершена."]; }];
}

- (void)connectModel:(id<AIBudsDeviceConvertible>)model {
    if(self.connecting)return;self.device=model;self.connecting=YES;self.autoProbe=YES;self.autoChecked=NO;
    NSDictionary *saved=[NSUserDefaults.standardUserDefaults dictionaryForKey:[self powerKey]];self.powerStatus=saved?[saved mutableCopy]:[NSMutableDictionary new];self.freshPowerComponents=[NSMutableSet new];[self refreshPower];
    AIBudsConnectParams *params=[AIBudsConnectParams new];params.userId=@"astor-local-probe";
    [model connectWithParams:params];[self log:@"Подключаю распознанную модель очков через SDK."];
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW,30*NSEC_PER_SEC),dispatch_get_main_queue(),^{
        if(self.device!=model || !self.connecting)return;
        self.connecting=NO;if(!model.isConnectedAndReady){[model disconnect];[self log:@"Ready не достигнут за 30 секунд. Включите очки и нажмите подключение снова."];}
    });
}
- (void)findGlasses {
    if(self.device.isConnectedAndReady){[self log:@"Очки уже подключены."];return;}
    if(self.connecting){[self log:@"Подключение уже выполняется."];return;}
    NSArray<id<AIBudsDeviceConvertible>> *saved=AIBudsStoredDevicesMgr.allDevices;
    if(saved.count==1){[self log:@"Повторное подключение к сохранённым очкам."];[self connectModel:saved.firstObject];return;}
    [AIBudsSDK stopScanning];
    if(self.rawCentral){[self.rawFound removeAllObjects]; [self centralManagerDidUpdateState:self.rawCentral];}
    else {self.rawFound=[NSMutableSet new]; self.rawCentral=[[CBCentralManager alloc]initWithDelegate:self queue:dispatch_get_main_queue()];}
}
- (NSDictionary *)keyQuery {return @{(__bridge id)kSecClass:(__bridge id)kSecClassGenericPassword,(__bridge id)kSecAttrService:@"com.astor.glasses.backend",(__bridge id)kSecAttrAccount:@"access-token"};}
- (void)importPilotAccess {
#if DEBUG
    NSString *path=[NSSearchPathForDirectoriesInDomains(NSDocumentDirectory,NSUserDomainMask,YES).firstObject stringByAppendingPathComponent:@".astor-backend-access.json"];
    if(![NSFileManager.defaultManager fileExistsAtPath:path])return;
    [NSFileManager.defaultManager setAttributes:@{NSFileProtectionKey:NSFileProtectionComplete} ofItemAtPath:path error:nil];
    NSData *data=[NSData dataWithContentsOfFile:path];
    BOOL removed=[NSFileManager.defaultManager removeItemAtPath:path error:nil];
    NSDictionary *access=data.length<=4096?[NSJSONSerialization JSONObjectWithData:data options:0 error:nil]:nil;
    if(!removed || ![access isKindOfClass:NSDictionary.class]){NSLog(@"[AstorGlasses] Файл доступа не принят; токен не изменён.");return;}
    NSString *base=access[@"baseURL"],*token=access[@"bearerToken"],*expiry=access[@"expiresAt"];
    if(![base isKindOfClass:NSString.class] || ![token isKindOfClass:NSString.class] || token.length<24 || token.length>1024 || ![expiry isKindOfClass:NSString.class])return;
    NSURLComponents *url=[NSURLComponents componentsWithString:base];NSISO8601DateFormatter *dates=[NSISO8601DateFormatter new];dates.formatOptions=NSISO8601DateFormatWithInternetDateTime|NSISO8601DateFormatWithFractionalSeconds;NSDate *expires=[dates dateFromString:expiry];if(!expires){dates.formatOptions=NSISO8601DateFormatWithInternetDateTime;expires=[dates dateFromString:expiry];}
    if(![url.scheme isEqual:@"https"] || ![url.host isEqual:@"c3ag.ru"] || url.user || url.password || url.query || url.fragment || url.port || (url.path.length && ![url.path isEqual:@"/"]) || !expires || expires.timeIntervalSinceNow<=0){NSLog(@"[AstorGlasses] Файл доступа отклонён: адрес или срок действия.");return;}
    NSDictionary *attributes=@{(__bridge id)kSecValueData:[token dataUsingEncoding:NSUTF8StringEncoding],(__bridge id)kSecAttrAccessible:(__bridge id)kSecAttrAccessibleWhenUnlockedThisDeviceOnly};
    OSStatus status=SecItemUpdate((__bridge CFDictionaryRef)[self keyQuery],(__bridge CFDictionaryRef)attributes);
    if(status==errSecItemNotFound){NSMutableDictionary *item=[[self keyQuery] mutableCopy];[item addEntriesFromDictionary:attributes];status=SecItemAdd((__bridge CFDictionaryRef)item,NULL);}
    if(status==errSecSuccess){[NSUserDefaults.standardUserDefaults setObject:base forKey:@"backendURL"];self.endpoint.text=base;NSLog(@"[AstorGlasses] Доступ к пилотному Astor сохранён в Keychain; файл импорта удалён.");}
    else NSLog(@"[AstorGlasses] Keychain не принял доступ: %d.",(int)status);
#endif
}
- (NSString *)token {
    if(self.pocketMode && self.sessionToken.length)return self.sessionToken;
    NSMutableDictionary *query=[[self keyQuery] mutableCopy];query[(__bridge id)kSecReturnData]=@YES;CFTypeRef result=NULL;
    if(SecItemCopyMatching((__bridge CFDictionaryRef)query,&result)!=errSecSuccess)return nil;
    NSData *data=CFBridgingRelease(result);return [[NSString alloc]initWithData:data encoding:NSUTF8StringEncoding];
}
- (void)credentials {
    UIAlertController *alert=[UIAlertController alertControllerWithTitle:@"Доступ к Astor" message:@"Токен тестового API от команды backend. Хранится в связке ключей этого iPhone." preferredStyle:UIAlertControllerStyleAlert];
    [alert addTextFieldWithConfigurationHandler:^(UITextField *f){f.secureTextEntry=YES;f.placeholder=@"Токен доступа";f.autocorrectionType=UITextAutocorrectionTypeNo;}];
    [alert addAction:[UIAlertAction actionWithTitle:@"Отмена" style:UIAlertActionStyleCancel handler:nil]];
    [alert addAction:[UIAlertAction actionWithTitle:@"Сохранить" style:UIAlertActionStyleDefault handler:^(UIAlertAction *action){
        NSString *value=[alert.textFields.firstObject.text stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
        self.sessionToken=nil;NSMutableDictionary *query=[[self keyQuery] mutableCopy];SecItemDelete((__bridge CFDictionaryRef)query);
        if(value.length){query[(__bridge id)kSecValueData]=[value dataUsingEncoding:NSUTF8StringEncoding];query[(__bridge id)kSecAttrAccessible]=(__bridge id)kSecAttrAccessibleWhenUnlockedThisDeviceOnly;[self log:SecItemAdd((__bridge CFDictionaryRef)query,NULL)==errSecSuccess?@"Токен сохранён.":@"Ошибка сохранения токена."];}
        else [self log:@"Токен удалён."];
    }]];[self presentViewController:alert animated:YES completion:nil];
}
- (NSURL *)validEndpoint:(BOOL)report {
    NSString *base=[self.endpoint.text stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];NSURLComponents *p=[NSURLComponents componentsWithString:base];
    if(![p.scheme.lowercaseString isEqual:@"https"] || !p.host.length || p.user || p.password || p.query || p.fragment || ![self token].length){if(report)[self log:@"Укажите HTTPS-адрес backend и сохраните токен. API требуется подготовить на VM."];return nil;}
    [NSUserDefaults.standardUserDefaults setObject:base forKey:@"backendURL"];
    p.path=[@"/" stringByAppendingString:[[p.path stringByTrimmingCharactersInSet:[NSCharacterSet characterSetWithCharactersInString:@"/"]] stringByAppendingString:@"/api/glasses/assist"]];
    if([p.path hasPrefix:@"//"])p.path=[p.path substringFromIndex:1];return p.URL;
}
- (void)takePhoto {
    [self beginPhotoForGuide:self.lunch.active?self.lunch.photoContext:nil];
}
- (void)beginPhotoForGuide:(NSDictionary *)context {
    if(self.dock.busy){[self log:@"Дождитесь импорта записей с очков."];return;}
    if(self.callActive || self.audioInterruptionActive){[self log:@"Во время звонка или другого аудио фото Астор не запускается."];return;}
    if(self.busy || self.waitingPhoto || self.recorder || self.startingVoice){[self log:@"Дождитесь запроса или отмените его."];return;}
    if(self.photoDeadline.timeIntervalSinceNow>0){[self log:@"Предыдущее фото отменено. Дождитесь окончания его передачи перед новой съёмкой."];return;}
    if(!self.device.isConnectedAndReady || ![self.device conformsToProtocol:@protocol(AIBudsDeviceCameraAPI)]){[self log:@"Сначала подключите очки."];return;}
    if(context && ![self validEndpoint:YES])return;
    [self clearPendingPhoto];
    self.photoContext=context;self.photoDeadline=[NSDate dateWithTimeIntervalSinceNow:30];
    [self beginWork];self.preview.image=nil;self.waitingPhoto=YES;NSUInteger generation=++self.photoGeneration;
    [self log:@"Снимаю один кадр. Постоянная камера выключена."];
    [(id<AIBudsDeviceCameraAPI>)self.device requestPhotoTakingWithCaptureMode:AIBudsCaptureModeAi completion:^(BOOL success,NSNumber *status,NSError *error){dispatch_async(dispatch_get_main_queue(),^{if(generation!=self.photoGeneration)return;if(!success){self.waitingPhoto=NO;self.photoContext=nil;self.photoDeadline=nil;[self endWork];[self log:[NSString stringWithFormat:@"Очки отклонили фото: status=%@, error=%ld.",status?:@"нет",(long)error.code]];}else [self log:@"Команда фото принята; ожидаю JPEG с очков."];});}];
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW,30*NSEC_PER_SEC),dispatch_get_main_queue(),^{if(generation==self.photoGeneration && self.waitingPhoto){self.waitingPhoto=NO;self.photoContext=nil;self.photoDeadline=nil;[self endWork];[self log:@"Фото не получено за 30 секунд."];}});
}
- (void)receivePhoto:(NSData *)data {
    dispatch_async(dispatch_get_main_queue(),^{if(!self.waitingPhoto)return;self.waitingPhoto=NO;
        NSDictionary *context=self.photoContext;self.photoContext=nil;self.photoDeadline=nil;
        if(context && ![self.lunch acceptsPhotoContext:context]){[self endWork];[self log:@"Шаг подачи изменился. Кадр не отправлен."];return;}
        if(data.length>12*1024*1024){[self endWork];[self log:@"Слишком большое фото."];return;}
        UIImage *image=[UIImage imageWithData:data];if(!image || image.size.width<1 || image.size.height<1){[self endWork];[self log:@"Некорректное фото."];return;}
        CGFloat ratio=MIN(1.0,1280.0/MAX(image.size.width,image.size.height));CGSize size=CGSizeMake(image.size.width*ratio,image.size.height*ratio);
        UIGraphicsBeginImageContextWithOptions(size,YES,1);[image drawInRect:(CGRect){CGPointZero,size}];UIImage *small=UIGraphicsGetImageFromCurrentImageContext();UIGraphicsEndImageContext();self.preview.image=small;self.preview.hidden=NO;self.frames++;
        [self log:@"Фото с очков получено."];
        NSString *prompt=context[@"prompt"]?:@"Что я вижу? Опиши кратко на русском; укажи, если детали неразличимы.";
        NSData *jpeg=UIImageJPEGRepresentation(small,0.75);if(!jpeg.length || jpeg.length>2*1024*1024){[self endWork];[self log:@"Фото не удалось подготовить в пределах размера API."];return;}
        if(![self.dock capturePhotoData:jpeg])[self log:@"Кадр для анализа остаётся в памяти; архивная сессия не открыта или сохранение недоступно."];
        NSString *requestId=NSUUID.UUID.UUIDString.lowercaseString;
        if(context){self.pendingPhotoImage=jpeg;self.pendingPhotoContext=context;self.pendingPhotoRequestId=requestId;self.pendingPhotoCreated=NSDate.date;
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW,110*NSEC_PER_SEC),dispatch_get_main_queue(),^{if([self.pendingPhotoRequestId isEqual:requestId]){[self clearPendingPhoto];[self refreshLunch];}});}
        [self refreshLunch];
        if([self validEndpoint:NO])[self sendText:prompt image:jpeg audio:nil photoContext:context requestId:requestId];else {[self log:@"Фото получено. Анализ станет доступен после подключения Astor."];[self endWork];}
    });
}
- (void)device:(id<AIBudsDeviceConvertible>)device didReceivePhotoDataForSceneRecognition:(NSData *)data enhancedPhotoData:(NSData *)enhanced error:(NSError *)error {if(device!=self.device)return;if(error){dispatch_async(dispatch_get_main_queue(),^{if(!self.waitingPhoto)return;self.waitingPhoto=NO;self.photoContext=nil;self.photoDeadline=nil;[self endWork];[self log:@"Ошибка передачи фото."];});return;}[self receivePhoto:enhanced?:data];}
- (void)device:(id<AIBudsDeviceConvertible>)device didReceiveInstantPhotoData:(NSData *)data error:(NSError *)error {if(device==self.device && !error)[self receivePhoto:data];}
- (void)ask {NSString *text=[self.question.text stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];if(!text.length){[self log:@"Введите вопрос или запишите голос."];return;}[self sendText:text image:nil audio:nil];}
- (void)sendText:(NSString *)text image:(NSData *)image audio:(NSData *)audio {
    [self sendText:text image:image audio:audio photoContext:nil requestId:NSUUID.UUID.UUIDString.lowercaseString];
}
- (void)sendText:(NSString *)text image:(NSData *)image audio:(NSData *)audio photoContext:(NSDictionary *)context requestId:(NSString *)requestId {
    if(self.callActive || self.audioInterruptionActive){[self log:@"Дождитесь окончания звонка или другого аудио перед запросом."];return;}
    if(self.busy){[self log:@"Запрос уже выполняется."];return;}if(text.length>4000 || image.length>2*1024*1024 || audio.length>2*1024*1024){[self log:@"Запрос превышает допустимый размер."];return;}NSURL *url=[self validEndpoint:YES];if(!url){[self endWork];return;}[self beginWork];
    if(context && ![self.lunch acceptsPhotoContext:context]){[self endWork];[self log:@"Шаг изменился; фото не отправлено."];return;}
    NSUInteger generation=++self.requestGeneration;
    NSMutableDictionary *body=[@{@"requestId":requestId,@"text":text?:@""} mutableCopy];if(image){body[@"imageBase64"]=[image base64EncodedStringWithOptions:0];body[@"imageMimeType"]=@"image/jpeg";}if(audio){body[@"audioBase64"]=[audio base64EncodedStringWithOptions:0];body[@"audioMimeType"]=@"audio/mp4";}
    if(context)body[@"photoContext"]=context[@"wire"];
    NSMutableURLRequest *request=[NSMutableURLRequest requestWithURL:url];request.HTTPMethod=@"POST";request.timeoutInterval=60;[request setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];[request setValue:[@"Bearer " stringByAppendingString:[self token]] forHTTPHeaderField:@"Authorization"];request.HTTPBody=[NSJSONSerialization dataWithJSONObject:body options:0 error:nil];
    NSURLSessionConfiguration *config=NSURLSessionConfiguration.ephemeralSessionConfiguration;config.URLCache=nil;self.http=[NSURLSession sessionWithConfiguration:config delegate:(id<NSURLSessionDelegate>)self delegateQueue:nil];self.busy=YES;[self log:@"Отправляю запрос Astor…"];
    [self.view endEditing:YES];
    NSURLSession *session=self.http;
    self.request=[self.http dataTaskWithRequest:request completionHandler:^(NSData *data,NSURLResponse *response,NSError *error){dispatch_async(dispatch_get_main_queue(),^{
        [session finishTasksAndInvalidate];if(generation!=self.requestGeneration)return;
        self.busy=NO;self.request=nil;[self endWork];self.http=nil;[self refreshLunch];
        if(context && ![self.lunch acceptsPhotoContext:context]){[self log:@"Ответ прежнего шага не принят."];return;}
        if(error){[self log:error.code==NSURLErrorCancelled?@"Запрос отменён.":context?@"Ошибка соединения. Можно повторить отправку этого фото без новой съёмки.":@"Ошибка соединения с Astor."];return;}
        NSInteger status=((NSHTTPURLResponse *)response).statusCode;if(status!=200){[self log:[NSString stringWithFormat:@"Astor: HTTP %ld. Фото шага не подтверждено. При временной ошибке нажмите повтор отправки.",(long)status]];return;}
        NSDictionary *reply=data.length<=3*1024*1024?[NSJSONSerialization JSONObjectWithData:data options:0 error:nil]:nil;NSString *answer=[reply isKindOfClass:NSDictionary.class]?reply[@"text"]:nil;
        if(!AstorAssistReplyMatches(reply,requestId)){[self log:@"Ответ не соответствует запросу или согласованному контракту."];return;}
        if(context){if(![self.lunch acceptPhotoReceipt:reply[@"photoReceipt"] context:context requestId:requestId]){[self log:@"Нет подтверждения сохранения фото для этого шага. Переход остаётся закрыт."];return;}[self clearPendingPhoto];[self refreshLunch];[self log:[NSString stringWithFormat:@"Фото текущего шага сохранено сервером. Всего шагов с фото: %lu.",(unsigned long)self.lunch.photoCount]];}
        self.lastAnswer=answer;self.answerLabel.text=answer;self.statusLabel.text=@"Butler ответил"; // Assistant content stays in memory, outside diagnostics.
        NSString *encoded=reply[@"audioBase64"],*mime=reply[@"audioMimeType"],*gender=reply[@"audioVoiceGender"];
        if([encoded isKindOfClass:NSString.class] && encoded.length && encoded.length<=((2*1024*1024+2)/3)*4 && [gender isEqual:@"male"] && ([mime isEqual:@"audio/mpeg"] || [mime isEqual:@"audio/mp4"])){
            NSData *speech=[[NSData alloc]initWithBase64EncodedString:encoded options:0];
            if(speech.length && speech.length<=2*1024*1024){[self playBackendSpeech:speech];return;}
        }
        [self speakAnswer:answer];
    });}];[self.request resume];
}
- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task willPerformHTTPRedirection:(NSHTTPURLResponse *)response newRequest:(NSURLRequest *)request completionHandler:(void (^)(NSURLRequest *))completionHandler {completionHandler(nil);}
- (void)playBackendSpeech:(NSData *)data {
    [self audioRoute];NSUInteger generation=self.photoGeneration;[self.speaker stopSpeakingAtBoundary:AVSpeechBoundaryImmediate];[self.answerAudio stop];
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW,500*NSEC_PER_MSEC),dispatch_get_main_queue(),^{if(generation!=self.photoGeneration)return;
        if(![self hasGlassesOutput]){[self log:@"Ответ на экране. Подключите аудиовыход AI Glasses для озвучки."];return;}
        self.answerAudio=[[AVAudioPlayer alloc]initWithData:data error:nil];self.answerAudio.delegate=self;
        if(![self.answerAudio play]){self.answerAudio=nil;[self log:@"Аудиоответ Astor не воспроизводится. Текст доступен на экране."];return;}
        [self log:@"Озвучиваю мужской аудиоответ Astor в очках."];
    });
}
- (NSArray<AVSpeechSynthesisVoice *> *)russianVoices {
    NSMutableArray *voices=[NSMutableArray new];for(AVSpeechSynthesisVoice *v in AVSpeechSynthesisVoice.speechVoices)if([v.language.lowercaseString hasPrefix:@"ru"])[voices addObject:v];
    return [voices sortedArrayUsingComparator:^NSComparisonResult(AVSpeechSynthesisVoice *a,AVSpeechSynthesisVoice *b){if(a.quality!=b.quality)return a.quality>b.quality?NSOrderedAscending:NSOrderedDescending;return [a.name compare:b.name];}];
}
- (AVSpeechSynthesisVoice *)russianVoice {
    NSArray<AVSpeechSynthesisVoice *> *voices=[self russianVoices];NSString *family=[NSUserDefaults.standardUserDefaults stringForKey:@"AstorSpeechFamily"]?:@"Milena";
    family=[family componentsSeparatedByString:@" ("].firstObject;
    for(AVSpeechSynthesisVoice *voice in voices)if([[voice.name componentsSeparatedByString:@" ("].firstObject isEqualToString:family])return voice;
    NSString *saved=[NSUserDefaults.standardUserDefaults stringForKey:@"AstorSpeechVoice"];
    for(AVSpeechSynthesisVoice *voice in voices)if([voice.identifier isEqualToString:saved])return voice;
    return voices.firstObject;
}
- (void)openShortcuts {[UIApplication.sharedApplication openURL:[NSURL URLWithString:@"shortcuts://"] options:@{} completionHandler:nil];}
- (void)chooseRussianVoice {
    NSArray *voices=[self russianVoices];UIAlertController *a=[UIAlertController alertControllerWithTitle:@"Голос Butler" message:voices.count?@"Русские голоса, доступные приложению на этом iPhone. При загрузке улучшенной версии выбранного голоса она используется автоматически.":@"Скачайте русский голос в Settings → Accessibility → Spoken Content → Voices → Russian." preferredStyle:UIAlertControllerStyleAlert];
    for(AVSpeechSynthesisVoice *voice in voices)[a addAction:[UIAlertAction actionWithTitle:[NSString stringWithFormat:@"%@%@",voice.name,voice.quality>AVSpeechSynthesisVoiceQualityDefault?@" · улучшенный":@""] style:UIAlertActionStyleDefault handler:^(UIAlertAction *action){[NSUserDefaults.standardUserDefaults setObject:voice.identifier forKey:@"AstorSpeechVoice"];[NSUserDefaults.standardUserDefaults setObject:voice.name forKey:@"AstorSpeechFamily"];self.voiceLabel.text=[NSString stringWithFormat:@"Голос Butler · %@%@",voice.name,voice.quality>AVSpeechSynthesisVoiceQualityDefault?@" · улучшенный":@""];[self.speaker stopSpeakingAtBoundary:AVSpeechBoundaryImmediate];[self testSpeaker];}]];
    [a addAction:[UIAlertAction actionWithTitle:@"Закрыть" style:UIAlertActionStyleCancel handler:nil]];[self presentViewController:a animated:YES completion:nil];
}
- (void)speakAnswer:(NSString *)answer {
    if(self.dock.busy){[self log:@"Дождитесь импорта перед озвучкой."];return;}
    if(self.callActive || self.audioInterruptionActive){[self log:@"Озвучка отложена: идёт звонок или другое аудио. Ответ сохранён на экране."];return;}
    [self audioRoute];BOOL output=NO;
    for(AVAudioSessionPortDescription *port in AVAudioSession.sharedInstance.currentRoute.outputs)
        if(([port.portType isEqual:AVAudioSessionPortBluetoothHFP] || [port.portType isEqual:AVAudioSessionPortBluetoothA2DP]) && ([port.portName.lowercaseString containsString:@"ai glasses"] || [port.portName.lowercaseString containsString:@"563b"]))output=YES;
    if(!output){[self log:@"Ответ на экране. Динамики очков не подключены как Bluetooth-аудио."];return;}
    AVSpeechSynthesisVoice *voice=[self russianVoice];if(!voice){[self log:@"Русский голос недоступен. Ответ пока на экране."];return;}self.speaker=self.speaker?:[AVSpeechSynthesizer new];self.speaker.delegate=self;AVSpeechUtterance *speech=[AVSpeechUtterance speechUtteranceWithString:answer];speech.voice=voice;[self.speaker speakUtterance:speech];
}
- (void)speechSynthesizer:(AVSpeechSynthesizer *)synthesizer didStartSpeechUtterance:(AVSpeechUtterance *)utterance {[self log:[NSString stringWithFormat:@"Озвучка: %@, quality=%ld.",utterance.voice.name,(long)utterance.voice.quality]];}
- (void)speechSynthesizer:(AVSpeechSynthesizer *)synthesizer didFinishSpeechUtterance:(AVSpeechUtterance *)utterance {[self log:@"Озвучка завершена."];}
- (void)toggleGlassesVoice {
    self.glassesVoiceEnabled=!self.glassesVoiceEnabled;
    if(self.glassesVoiceEnabled)[self log:@"Обработка голосовых событий SDK включена. Нажмите привычный жест вызова помощника на очках. Кнопки не переназначаются; работа при заблокированном телефоне ещё не проверена."];
    else {[self cancelAgent];[self log:@"Голосовые события SDK выключены."];}
}
- (void)device:(id<AIBudsDeviceConvertible>)device didReceiveAiChatSessionEvent:(enum AIBudsAIChatSessionEvent)event {
    dispatch_async(dispatch_get_main_queue(),^{
        if(device!=self.device)return;
        [self log:[NSString stringWithFormat:@"Голосовое событие SDK: %ld.",(long)event]];
        if(!self.glassesVoiceEnabled)return;
        if(event==AIBudsAIChatSessionEventTerminate){if(self.recorder)[self finishVoice];return;}
        if(event==AIBudsAIChatSessionEventInterruptByStateConflict){[self cancelAgent];return;}
        if(event==AIBudsAIChatSessionEventInitiateWithSCO){
            if(!self.pocketMode && UIApplication.sharedApplication.applicationState!=UIApplicationStateActive){[self log:@"Фоновый голосовой режим требует отдельного теста; запись не начата."];return;}
            if(!self.recorder)[self voice];
        } else if(event==AIBudsAIChatSessionEventInitiateWithOpus){
            [self log:@"Очки запросили Opus-канал; для этого пилота проверен HFP. Opus-сессия не запускается."];
            if([device conformsToProtocol:@protocol(AIBudsDeviceAIChatAPI)])[(id<AIBudsDeviceAIChatAPI>)device reportAIChatStartFailureWithCompletion:nil];
        }
    });
}
- (void)device:(id<AIBudsDeviceConvertible>)device didReceiveOpusAudioData:(NSData *)opus decodedPCMAudioData:(NSData *)pcm purpose:(enum AIBudsOpusAudioDataPurpose)purpose {
    dispatch_async(dispatch_get_main_queue(),^{if(device==self.device){self.opusBytes+=opus.length;self.pcmBytes+=pcm.length;}});
}
- (void)testSpeaker {AVSpeechSynthesisVoice *voice=[self russianVoice];if(voice)[self log:[NSString stringWithFormat:@"Проверка озвучки: %@, quality=%ld.",voice.name,(long)voice.quality]];[self speakAnswer:@"Астор на связи. Это проверка голоса. Если вы слышите меня через очки, аудиовыход работает."];}
- (void)voice {
    if(self.dock.busy){[self log:@"Дождитесь импорта записей с очков."];return;}
    if(self.callActive || self.audioInterruptionActive){[self log:@"Во время звонка или другого аудио запись Астор не запускается."];return;}
    if(self.startingVoice)return;
    if(self.music){[self.music stop];self.music=nil;[self restoreAssistantMedia];}
    if(self.recorder){[self finishVoice];return;}if(self.busy || self.waitingPhoto || self.startingVoice)return;[self.answerAudio stop];self.answerAudio=nil;[self.speaker stopSpeakingAtBoundary:AVSpeechBoundaryImmediate];
    NSUInteger generation=self.photoGeneration;
    if(UIApplication.sharedApplication.applicationState!=UIApplicationStateActive && AVAudioSession.sharedInstance.recordPermission!=AVAudioSessionRecordPermissionGranted){[self log:@"Сначала разрешите микрофон с открытым приложением."];return;}
    self.startingVoice=YES;[AVAudioSession.sharedInstance requestRecordPermission:^(BOOL granted){dispatch_async(dispatch_get_main_queue(),^{
        self.startingVoice=NO;if(generation!=self.photoGeneration)return;
        if(!granted){[self log:@"Разрешите микрофон в Settings → Astor Glasses."];return;}[self audioRoute];AVAudioSession *session=AVAudioSession.sharedInstance;
        AVAudioSessionPortDescription *input=nil;for(AVAudioSessionPortDescription *p in session.availableInputs)if([p.portType isEqual:AVAudioSessionPortBluetoothHFP] && ([p.portName.lowercaseString containsString:@"ai glasses"] || [p.portName.lowercaseString containsString:@"563b"]))input=p;
        if(!input || ![session setPreferredInput:input error:nil]){[self log:@"Подключите AI Glasses как гарнитуру в Settings → Bluetooth: Bluetooth-микрофон недоступен."];return;}
        [self beginWork];self.recordingURL=[NSURL fileURLWithPath:[[NSTemporaryDirectory() stringByAppendingPathComponent:@"AstorVoice"] stringByAppendingPathComponent:[NSUUID.UUID.UUIDString stringByAppendingString:@".m4a"]]];
        self.recorder=[[AVAudioRecorder alloc]initWithURL:self.recordingURL settings:@{AVFormatIDKey:@(kAudioFormatMPEG4AAC),AVSampleRateKey:@16000,AVNumberOfChannelsKey:@1,AVEncoderBitRateKey:@32000} error:nil];
        self.recorder.meteringEnabled=NO;
        if(![self.recorder prepareToRecord]){[self log:@"Микрофон не готов к записи."];[self cancelAgent];return;}
        self.startingVoice=YES;[self playCue:YES];
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW,180*NSEC_PER_MSEC),dispatch_get_main_queue(),^{
            if(generation!=self.photoGeneration)return;self.startingVoice=NO;
            if(![self.recorder recordForDuration:30]){[self log:@"Ошибка записи."];[self cancelAgent];return;}[self log:@"Слушаю. Скажите команду; двойное нажатие завершит запись."];[self refresh];
            AVAudioRecorder *recording=self.recorder;dispatch_after(dispatch_time(DISPATCH_TIME_NOW,30*NSEC_PER_SEC),dispatch_get_main_queue(),^{if(self.recorder==recording)[self finishVoice];});
        });
    });}];
}
- (void)finishVoice {
    [self.recorder stop];self.recorder=nil;[self playCue:NO];NSData *audio=[NSData dataWithContentsOfURL:self.recordingURL];
    if(audio.length && audio.length<2*1024*1024)[self.dock captureAudioFile:self.recordingURL];
    if(self.recordingURL)[NSFileManager.defaultManager removeItemAtURL:self.recordingURL error:nil];self.recordingURL=nil;
    if(audio.length && audio.length<2*1024*1024 && [self replyWanted]){[self log:@"Ответ записан; расшифровываю для черновика."];[self transcribeReply:audio];return;}
    if(audio.length && audio.length<2*1024*1024){[self log:@"Голосовая запись получена; временный файл удалён."];if([self validEndpoint:NO])[self sendText:@"" image:nil audio:audio];else {[self log:@"Голос записан. Для ответа подключите Astor."];[self speakAnswer:@"Запись получена. Сервер Butler ещё не подключён."];[self endWork];}}else {[self log:@"Запись пуста или слишком велика."];[self endWork];}
}
/* The staff member's answer becomes text and stops there: no model call, no sending, no acknowledgement. */
- (void)transcribeReply:(NSData *)audio {
    AstorQuietDeliveryMessage *message=self.answeringMessage;
    self.answeringMessage=nil;self.replyDeadline=nil;
    NSURL *url=[self transcribeEndpoint];NSString *token=[self token];
    if(!url || !token.length){[self endWork];[self log:@"Расшифровка недоступна: проверьте адрес и токен."];return;}
    NSString *requestId=NSUUID.UUID.UUIDString.lowercaseString;
    NSMutableURLRequest *request=[NSMutableURLRequest requestWithURL:url];request.HTTPMethod=@"POST";request.timeoutInterval=45;
    [request setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
    [request setValue:[@"Bearer " stringByAppendingString:token] forHTTPHeaderField:@"Authorization"];
    request.HTTPBody=[NSJSONSerialization dataWithJSONObject:@{@"requestId":requestId,
        @"audioBase64":[audio base64EncodedStringWithOptions:0],@"audioMimeType":@"audio/mp4"} options:0 error:nil];
    NSURLSessionConfiguration *config=NSURLSessionConfiguration.ephemeralSessionConfiguration;config.URLCache=nil;
    NSURLSession *session=[NSURLSession sessionWithConfiguration:config delegate:(id<NSURLSessionDelegate>)self delegateQueue:nil];
    self.busy=YES;
    [[session dataTaskWithRequest:request completionHandler:^(NSData *data,NSURLResponse *response,NSError *error){dispatch_async(dispatch_get_main_queue(),^{
        [session finishTasksAndInvalidate];self.busy=NO;[self endWork];
        NSInteger status=((NSHTTPURLResponse *)response).statusCode;
        if(error || status!=200){[self log:[NSString stringWithFormat:@"Ответ не расшифрован: %@.",error?@"нет связи":[NSString stringWithFormat:@"HTTP %ld",(long)status]]];
            [self speakAnswer:@"Не удалось разобрать ответ. Попробуйте ещё раз."];return;}
        NSDictionary *reply=data.length<=256*1024?[NSJSONSerialization JSONObjectWithData:data options:0 error:nil]:nil;
        NSString *text=[reply isKindOfClass:NSDictionary.class]?reply[@"text"]:nil;
        if(![text isKindOfClass:NSString.class] || !text.length || ![reply[@"requestId"] isEqual:requestId]){
            [self log:@"Расшифровка не соответствует запросу."];return;}
        AstorReplyDraft *draft=[AstorReplyDraft answering:message.messageId asked:message.text text:text at:NSDate.date];
        if(![self.drafts add:draft]){[self log:@"Черновик не сохранён."];return;}
        [self refreshDrafts];
        [self log:[NSString stringWithFormat:@"Черновик ответа готов: %@",draft.text]];
        [self speakAnswer:@"Ответ записан. Отправите его из Telegram, когда посмотрите на телефон."];
    });}] resume];
}
- (NSURL *)transcribeEndpoint {
    NSURL *assist=[self validEndpoint:NO];
    if(!assist)return nil;
    NSURLComponents *components=[NSURLComponents componentsWithURL:assist resolvingAgainstBaseURL:NO];
    if(![components.path hasSuffix:@"/assist"])return nil;
    components.path=[[components.path substringToIndex:components.path.length-@"assist".length] stringByAppendingString:@"transcribe"];
    return components.URL;
}
- (void)playCue:(BOOL)start {
    if(![self hasGlassesOutput])return;
    const uint32_t count=1920,rate=16000,bytes=count*2;NSMutableData *wave=[NSMutableData dataWithLength:44+bytes];uint8_t *raw=wave.mutableBytes;
    memcpy(raw,"RIFF",4);uint32_t total=36+bytes;memcpy(raw+4,&total,4);memcpy(raw+8,"WAVEfmt ",8);uint32_t fmt=16;memcpy(raw+16,&fmt,4);uint16_t pcm=1,channels=1,bits=16,align=2;memcpy(raw+20,&pcm,2);memcpy(raw+22,&channels,2);memcpy(raw+24,&rate,4);uint32_t speed=rate*2;memcpy(raw+28,&speed,4);memcpy(raw+32,&align,2);memcpy(raw+34,&bits,2);memcpy(raw+36,"data",4);memcpy(raw+40,&bytes,4);
    for(uint32_t i=0;i<count;i++){double fade=MIN(1.,MIN(i/160.,(count-1-i)/160.));int16_t value=(int16_t)(4000*fade*sin(2*M_PI*(start?880:440)*i/rate));memcpy(raw+44+i*2,&value,2);}
    [self.cue stop];self.cue=[[AVAudioPlayer alloc]initWithData:wave error:nil];self.cue.volume=.35;[self.cue play];
}
- (BOOL)hasGlassesOutput {
    for(AVAudioSessionPortDescription *p in AVAudioSession.sharedInstance.currentRoute.outputs)if(([p.portType isEqual:AVAudioSessionPortBluetoothHFP] || [p.portType isEqual:AVAudioSessionPortBluetoothA2DP]) && ([p.portName.lowercaseString containsString:@"ai glasses"] || [p.portName.lowercaseString containsString:@"563b"]))return YES;return NO;
}
- (void)syncPhoneCalls {
    self.phoneCallActive=NO;for(CXCall *call in self.callObserver.calls)if(!call.hasEnded){self.phoneCallActive=YES;break;}[self updateCallState];
}
- (void)callObserver:(CXCallObserver *)observer callChanged:(CXCall *)call {[self syncPhoneCalls];}
- (void)device:(id<AIBudsDeviceConvertible>)device didCallStatusChanged:(enum AIBudsCallStatus)status {
    dispatch_async(dispatch_get_main_queue(),^{if(device!=self.device)return;
        [self syncPhoneCalls];
        BOOL ownHFP=self.recorder!=nil || self.startingVoice || self.speaker.isSpeaking || self.answerAudio.isPlaying || self.cue.isPlaying;
        BOOL ringing=status==AIBudsCallStatusRinging || status==AIBudsCallStatusThreeWayRinging;
        self.glassesCallActive=AstorCallShouldInterrupt(NO,ringing,status==AIBudsCallStatusInCall,ownHFP);
        [self log:[NSString stringWithFormat:@"Состояние звонка SDK: %ld; системный звонок=%@, собственное HFP=%@.",(long)status,self.phoneCallActive?@"да":@"нет",ownHFP?@"да":@"нет"]];
        if(status==AIBudsCallStatusInCall && ownHFP && !self.phoneCallActive && !ringing)[self log:@"SDK InCall относится к нашему Bluetooth-аудио; запись не прерывается."];
        [self updateCallState];
    });
}
- (NSString *)powerKey {return [@"AstorPower." stringByAppendingString:self.device.uuid.UUIDString?:@"unknown"];}
- (NSString *)powerLine:(NSString *)name component:(NSString *)component {
    NSDictionary *entry=self.powerStatus[component];NSNumber *level=entry[@"level"];NSDate *at=entry[@"levelAt"];
    if(![level isKindOfClass:NSNumber.class] || ![at isKindOfClass:NSDate.class])return [name stringByAppendingString:@" · заряд ещё не получен"];
    BOOL fresh=self.device.isConnectedAndReady && [self.freshPowerComponents containsObject:component] && -at.timeIntervalSinceNow<120;
    BOOL charging=[entry[@"state"] integerValue]==AIBudsChargingStateCharging && self.device.isConnectedAndReady && [self.freshPowerComponents containsObject:component] && [entry[@"stateAt"] isKindOfClass:NSDate.class] && -[entry[@"stateAt"] timeIntervalSinceNow]<120;
    if(fresh)return [NSString stringWithFormat:@"%@ · %@%%%@",name,level,charging?@" · заряжается":@""];
    NSDateFormatter *date=[NSDateFormatter new];date.locale=[NSLocale localeWithLocaleIdentifier:@"ru_RU"];date.dateFormat=@"dd.MM HH:mm";
    return [NSString stringWithFormat:@"%@ · %@%% · последнее %@",name,level,[date stringFromDate:at]];
}
- (void)refreshPower {self.powerLabel.text=[NSString stringWithFormat:@"%@\n%@",[self powerLine:@"Очки" component:@"0"],[self powerLine:@"Кейс" component:@"3"]];}
- (void)refreshDock {
    BOOL foreground=UIApplication.sharedApplication.applicationState!=UIApplicationStateBackground;
    BOOL idle=!self.callActive && !self.audioInterruptionActive && !self.busy && !self.waitingPhoto && !self.recorder && !self.startingVoice && !self.assigning && !self.connecting && !self.speaker.isSpeaking && !self.answerAudio.isPlaying && !self.cue.isPlaying && !self.music.isPlaying;
    NSString *token=[self token];
    if(foreground || token.length)[self.dock configureBaseURL:[NSURL URLWithString:[NSUserDefaults.standardUserDefaults stringForKey:@"backendURL"]?:@""] bearer:token];
    [self.dock updateDevice:self.device foreground:foreground idle:idle];
}
- (void)device:(id<AIBudsDeviceConvertible>)device didBatteryStatusChanged:(AIBudsBatteryStatusModel *)status {
    dispatch_async(dispatch_get_main_queue(),^{if(device!=self.device)return;
        if(!self.powerStatus)self.powerStatus=[NSMutableDictionary new];if(!self.freshPowerComponents)self.freshPowerComponents=[NSMutableSet new];
        for(NSNumber *component in @[@(AIBudsBatteryComponentGlass),@(AIBudsBatteryComponentChargingCase)]){
            AIBudsBatteryInfoModel *info=[status infoForComponent:component.integerValue];if(!info)continue;
            if(component.integerValue==AIBudsBatteryComponentGlass && info.chargingState!=AIBudsChargingStateUnknown){self.dockCharging=info.chargingState==AIBudsChargingStateCharging;if(self.dockCharging)}
            [self refreshDock];[self.dock observeChargingForDevice:device component:component.integerValue state:info.chargingState];
            NSString *key=component.stringValue;NSDictionary *previous=self.powerStatus[key];NSMutableDictionary *entry=previous?[previous mutableCopy]:[NSMutableDictionary new];NSDate *now=NSDate.date;
            NSNumber *level=info.batteryLevel;BOOL valid=[level isKindOfClass:NSNumber.class] && isfinite(level.doubleValue) && level.doubleValue>=0 && level.doubleValue<=100 && floor(level.doubleValue)==level.doubleValue;
            if(valid){entry[@"level"]=level;entry[@"levelAt"]=now;}
            entry[@"state"]=@(info.chargingState);entry[@"stateAt"]=now;self.powerStatus[key]=entry;[self.freshPowerComponents addObject:key];
            if(![previous[@"level"] isEqual:entry[@"level"]] || ![previous[@"state"] isEqual:entry[@"state"]]){
                NSString *name=component.integerValue==AIBudsBatteryComponentGlass?@"Очки":@"Кейс";NSString *state=info.chargingState==AIBudsChargingStateCharging?@"заряжаются":info.chargingState==AIBudsChargingStateDischarging?@"не заряжаются":@"состояние зарядки неизвестно";
                NSDateFormatter *date=[NSDateFormatter new];date.dateFormat=@"dd.MM HH:mm:ss";[self log:[NSString stringWithFormat:@"%@ · %@: заряд %@, %@.",[date stringFromDate:now],name,valid?[NSString stringWithFormat:@"%@%%",level]:@"не передан",state]];
                NSDictionary *event=@{@"event":@"sdk_battery",@"at":[[NSISO8601DateFormatter new] stringFromDate:now],@"component":component,@"percent":valid?level:NSNull.null,@"chargingState":@(info.chargingState)};
                NSData *json=[NSJSONSerialization dataWithJSONObject:event options:0 error:nil];NSString *path=[NSSearchPathForDirectoriesInDomains(NSDocumentDirectory,NSUserDomainMask,YES).firstObject stringByAppendingPathComponent:@"power-events.jsonl"];
                if(![NSFileManager.defaultManager fileExistsAtPath:path])[NSFileManager.defaultManager createFileAtPath:path contents:nil attributes:nil];
                NSFileHandle *file=[NSFileHandle fileHandleForWritingAtPath:path];[file seekToEndOfFile];[file writeData:json];[file writeData:[@"\n" dataUsingEncoding:NSUTF8StringEncoding]];[file closeFile];
            }
        }
        [NSUserDefaults.standardUserDefaults setObject:self.powerStatus forKey:[self powerKey]];[self refreshPower];
    });
}
- (void)updateCallState {
    BOOL active=self.phoneCallActive || self.glassesCallActive;
    if(active==self.callActive)return;self.callActive=active;
    if(active){[self cancelAgent];self.callLabel.text=@"Звонок · два касания боковой панели — принять или завершить.\nЗапись и подсказки приостановлены.";[self log:@"Звонок: запись и озвучка Астор остановлены; текущий шаг сохранён."];}
    else {self.callLabel.text=@"Звонок завершён · повторите подсказку двойным нажатием справа";[self log:@"Звонок завершён. Запись и озвучка автоматически не возобновляются."];}
    [self refreshLunch];
}
- (void)audioInterrupted:(NSNotification *)notification {dispatch_async(dispatch_get_main_queue(),^{
    self.audioInterruptionActive=[notification.userInfo[AVAudioSessionInterruptionTypeKey] integerValue]==AVAudioSessionInterruptionTypeBegan;
    if(self.audioInterruptionActive){[self cancelAgent];[self log:@"Запись и звук остановлены: звонок или другое аудио."];}[self refreshLunch];
});}
- (void)device:(id<AIBudsDeviceConvertible>)device didDisconnectWithError:(NSError *)error {dispatch_async(dispatch_get_main_queue(),^{if(device==self.device){[self cancelAgent];self.glassesCallActive=NO;[self updateCallState];[self.freshPowerComponents removeAllObjects];[self refreshPower];self.gestureMapping=nil;[self refreshGestures];[self log:@"Очки отключились. Положение в кейсе SDK не сообщает; на экране оставлен последний полученный заряд."];}});}
- (void)routeChanged {
    dispatch_async(dispatch_get_main_queue(),^{
        if(![self hasGlassesOutput]){[self.speaker stopSpeakingAtBoundary:AVSpeechBoundaryImmediate];[self.cue stop];[self.answerAudio stop];}
        if(!self.recorder)return;
        BOOL glasses=NO;for(AVAudioSessionPortDescription *port in AVAudioSession.sharedInstance.currentRoute.inputs)if([port.portType isEqual:AVAudioSessionPortBluetoothHFP] && ([port.portName.lowercaseString containsString:@"ai glasses"] || [port.portName.lowercaseString containsString:@"563b"]))glasses=YES;
        if(!glasses){[self cancelAgent];[self log:@"Запись остановлена: Bluetooth-микрофон очков отключился."];}
    });
}
- (void)cancelAgent {
    if(self.speakingMessage){AstorQuietDeliveryMessage *interrupted=self.speakingMessage;self.speakingMessage=nil;[self.messages finishedSpeaking:interrupted at:NSDate.date delivered:NO];}
    [self stopSilenceMonitor];
    if(!self.pocketMode)self.sessionToken=nil;
    [self clearPendingPhoto];self.preview.image=nil;self.waitingPhoto=NO;self.photoContext=nil;self.startingVoice=NO;self.requestGeneration++;self.busy=NO;if(self.music){[self.music stop];self.music=nil;[self restoreAssistantMedia];}[self.answerAudio stop];self.answerAudio=nil;[self.cue stop];self.photoGeneration++;[self endWork];[self.recorder stop];self.recorder=nil;if(self.recordingURL)[NSFileManager.defaultManager removeItemAtURL:self.recordingURL error:nil];self.recordingURL=nil;[self.request cancel];self.request=nil;[self.http invalidateAndCancel];self.http=nil;[self.speaker stopSpeakingAtBoundary:AVSpeechBoundaryImmediate];[self stop];
}

- (NSString *)functionName:(NSNumber *)value {
    NSArray *names=@[@"Не назначено",@"Повторный звонок",@"Помощник устройства",@"Предыдущий трек / повтор Butler",@"Следующий трек / фото Butler",@"Громче",@"Тише",@"Play/Pause / голос Butler",@"Игровой режим",@"Шумоподавление",@"Фото на очках",@"Серия фото",@"Видео: старт / стоп",@"Аудиозапись: старт / стоп",@"Локальный / Bluetooth-звук"];
    return value.integerValue>=0 && value.integerValue<names.count?names[value.integerValue]:[NSString stringWithFormat:@"Функция %@",value];
}
- (NSString *)backupKey {return [@"AstorGestureBackup." stringByAppendingString:self.device.uuid.UUIDString?:@"unavailable"];}
- (void)refreshGestures {
    for(UIView *v in self.gestureRows.arrangedSubviews.copy){[self.gestureRows removeArrangedSubview:v];[v removeFromSuperview];}
    self.gestureHint.text=self.gestureMapping.count?@"Нажмите жест, чтобы выбрать функцию. Butler через медиакнопки работает только в тестовом режиме «Без экрана»; получение событий ещё нужно проверить. Одиночное фото, удержания и свайпы профиль не меняет.":@"Очки ещё не сообщили таблицу жестов. Назначения не меняем без неё.";
    for(NSNumber *operation in [[self.gestureMapping allKeys] sortedArrayUsingSelector:@selector(compare:)]){
        UIButton *b=[self button:[NSString stringWithFormat:@"%@\n%@",self.gestureNames[operation]?:[NSString stringWithFormat:@"Жест %@",operation],[self functionName:self.gestureMapping[operation]]] action:@selector(editGesture:)];b.tag=operation.integerValue;b.enabled=!self.assigning;[self.gestureRows addArrangedSubview:b];
    }
}
- (void)editGesture:(UIButton *)button {
    NSNumber *op=@(button.tag);if(self.assigning || !self.gestureMapping[op])return;
    UIAlertController *a=[UIAlertController alertControllerWithTitle:self.gestureNames[op]?:@"Жест очков" message:@"Функции фото и записи работают на самих очках. Голос/фото/повтор Butler через медиакнопки требуют активного тестового режима и проверки на устройстве." preferredStyle:UIAlertControllerStyleActionSheet];
    for(NSNumber *value in @[@7,@4,@3,@5,@6,@10,@12,@13,@2,@0]){
        [a addAction:[UIAlertAction actionWithTitle:[self functionName:value] style:UIAlertActionStyleDefault handler:^(UIAlertAction *action){[self assignChanges:@{op:value} restoring:NO];}]];
    }
    [a addAction:[UIAlertAction actionWithTitle:@"Отмена" style:UIAlertActionStyleCancel handler:nil]];a.popoverPresentationController.sourceView=button;a.popoverPresentationController.sourceRect=button.bounds;[self presentViewController:a animated:YES completion:nil];
}
- (void)applyButlerProfile {
    NSDictionary *profile=AstorGestureProfile(self.gestureMapping);
    if(!profile.count){[self log:@"Устройство не сообщило двойные/тройные жесты для профиля. Назначения не изменены."];return;}
    [self assignChanges:profile restoring:NO];
}
- (void)restoreGestures {
    NSDictionary *saved=[NSUserDefaults.standardUserDefaults dictionaryForKey:[self backupKey]];NSMutableDictionary *values=[NSMutableDictionary new];
    for(NSString *op in saved)values[@(op.integerValue)]=saved[op];
    if(!values.count){[self log:@"Сохранённых изменений нет."];return;}[self assignChanges:values restoring:YES];
}
- (void)assignChanges:(NSDictionary *)changes restoring:(BOOL)restoring {
    if(self.dock.busy){[self log:@"Дождитесь импорта перед настройкой жестов."];return;}
    if(self.assigning){[self log:@"Дождитесь настройки жестов."];return;}
    if(!self.device.isConnectedAndReady || ![self.device conformsToProtocol:@protocol(AIBudsDevicePhysicalOperationsAPI)]){[self log:@"Для настройки жестов подключите очки."];return;}
    NSDictionary *current=((id<AIBudsDevicePhysicalOperationsAPI>)self.device).physicalOperationsMapping;
    for(NSNumber *op in changes)if(!current[op]){[self log:@"Жест отсутствует в таблице устройства. Назначения не изменены."];return;}
    NSDictionary *saved=[NSUserDefaults.standardUserDefaults dictionaryForKey:[self backupKey]];
    if(!restoring)[NSUserDefaults.standardUserDefaults setObject:AstorGestureBackup(saved,current,changes) forKey:[self backupKey]];
    NSMutableDictionary *rollback=[NSMutableDictionary new];for(NSNumber *op in changes)rollback[op]=current[op];
    self.assigning=YES;NSUInteger generation=++self.bindingGeneration;[self refreshGestures];
    [self log:restoring?@"Восстанавливаю прежние назначения…":@"Сохранил прежние назначения. Настраиваю жесты…"];
    [self applySequence:[[changes allKeys] sortedArrayUsingSelector:@selector(compare:)] values:changes index:0 generation:generation rollback:restoring?nil:rollback];
}
- (void)applySequence:(NSArray *)keys values:(NSDictionary *)values index:(NSUInteger)index generation:(NSUInteger)generation rollback:(NSDictionary *)rollback {
    if(generation!=self.bindingGeneration)return;
    if(index==keys.count){self.assigning=NO;self.gestureMapping=((id<AIBudsDevicePhysicalOperationsAPI>)self.device).physicalOperationsMapping;BOOL matches=YES;for(NSNumber *op in keys)if(![self.gestureMapping[op] isEqual:values[op]])matches=NO;
        [self refreshGestures];[self log:matches?@"SDK подтвердил назначения. Следующий шаг — физический тест жестов.":@"Команды приняты, но таблица SDK ещё не подтверждает значения. Проверьте или восстановите назначения."];
        return;
    }
    NSNumber *op=keys[index];__block BOOL answered=NO;
    id<AIBudsDeviceConvertible> target=self.device;
    [(id<AIBudsDevicePhysicalOperationsAPI>)target assignFunction:(enum AIBudsDeviceFunction)[values[op] integerValue] forPhysicalOperation:(enum AIBudsDeviceOperation)op.integerValue completion:^(BOOL success,NSError *error){dispatch_async(dispatch_get_main_queue(),^{
        if(generation!=self.bindingGeneration || answered)return;answered=YES;
        if(success && !error && target==self.device){[self log:[NSString stringWithFormat:@"Назначение жеста %@ принято SDK.",op]];[self applySequence:keys values:values index:index+1 generation:generation rollback:rollback];}
        else if(rollback){[self log:@"Ошибка настройки. Возвращаю значения перед этой попыткой."];[self applySequence:keys values:rollback index:0 generation:generation rollback:nil];}
        else {self.assigning=NO;self.bindingGeneration++;[self refreshGestures];[self log:@"Восстановление не подтверждено. Резервные значения сохранены; переподключите очки и повторите."];}
    });}];
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW,8*NSEC_PER_SEC),dispatch_get_main_queue(),^{if(answered || generation!=self.bindingGeneration)return;answered=YES;self.assigning=NO;self.bindingGeneration++;[self refreshGestures];[self log:@"SDK не ответил на настройку. Состояние неизвестно; сохранена возможность восстановления."];});
}
- (void)beginWork {
    if(!self.pocketMode || self.backgroundTask!=UIBackgroundTaskInvalid)return;
    self.backgroundTask=[UIApplication.sharedApplication beginBackgroundTaskWithName:@"Astor bounded request" expirationHandler:^{[self cancelAgent];[self log:@"iOS завершила фоновое время. Откройте приложение для повторения."];}];
}
- (void)endWork {if(self.backgroundTask!=UIBackgroundTaskInvalid){UIBackgroundTaskIdentifier task=self.backgroundTask;self.backgroundTask=UIBackgroundTaskInvalid;[UIApplication.sharedApplication endBackgroundTask:task];}}
- (void)enteredBackground {if(!self.pocketMode)[self cancelAgent];else if(self.recorder || self.waitingPhoto || self.busy)[self beginWork];[self refreshDock];}
- (void)restoreAssistantMedia {
    MPNowPlayingInfoCenter.defaultCenter.nowPlayingInfo=self.pocketMode?@{MPMediaItemPropertyTitle:@"Astor • помощник на смене",MPMediaItemPropertyArtist:@"Голос / фото / повтор шага",MPNowPlayingInfoPropertyIsLiveStream:@YES}:nil;
    MPNowPlayingInfoCenter.defaultCenter.playbackState=MPNowPlayingPlaybackStatePaused;
}
- (NSURL *)musicURL {return [NSURL fileURLWithPath:[NSSearchPathForDirectoriesInDomains(NSDocumentDirectory,NSUserDomainMask,YES).firstObject stringByAppendingPathComponent:@"Ilkutki.m4a"]];}
- (void)playMusic {
    if(self.callActive || self.audioInterruptionActive){[self log:@"Во время звонка или другого аудио музыка не запускается."];return;}
    if(self.music){if(self.music.isPlaying){[self.music pause];[self log:@"Ilkutki · пауза"];}else {[self.music play];[self log:@"Ilkutki · играет"];}MPNowPlayingInfoCenter.defaultCenter.playbackState=self.music.isPlaying?MPNowPlayingPlaybackStatePlaying:MPNowPlayingPlaybackStatePaused;return;}
    if(![NSFileManager.defaultManager fileExistsAtPath:[self musicURL].path]){[self log:@"Трек Ilkutki ещё не перенесён на iPhone."];return;}
    [self cancelAgent];AVAudioSession *session=AVAudioSession.sharedInstance;
    if(![session setCategory:AVAudioSessionCategoryPlayback mode:AVAudioSessionModeDefault options:0 error:nil] || ![session setActive:YES error:nil]){[self log:@"Не удалось включить музыкальный аудиорежим."];return;}
    NSUInteger generation=self.photoGeneration;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW,800*NSEC_PER_MSEC),dispatch_get_main_queue(),^{if(generation!=self.photoGeneration)return;
        if(![self hasGlassesOutput]){[self log:@"Подключите аудиовыход AI Glasses: трек не запускается через динамик телефона."];return;}
        NSError *error=nil;self.music=[[AVAudioPlayer alloc]initWithContentsOfURL:[self musicURL] error:&error];self.music.delegate=self;self.music.volume=.25;
        if(!self.music || ![self.music play]){self.music=nil;[self log:@"Не удалось воспроизвести Ilkutki."];return;}
        MPNowPlayingInfoCenter.defaultCenter.nowPlayingInfo=@{MPMediaItemPropertyTitle:@"Ilkutki",MPMediaItemPropertyArtist:@"Michael Welly",MPMediaItemPropertyPlaybackDuration:@(self.music.duration),MPNowPlayingInfoPropertyElapsedPlaybackTime:@(self.music.currentTime),MPNowPlayingInfoPropertyPlaybackRate:@1};MPNowPlayingInfoCenter.defaultCenter.playbackState=MPNowPlayingPlaybackStatePlaying;
        [self log:@"Играет Michael Welly — Ilkutki в очках. Начальная громкость 25%. Двойное нажатие — пауза/продолжить."];
    });
}
- (void)audioPlayerDidFinishPlaying:(AVAudioPlayer *)player successfully:(BOOL)flag {if(player==self.music){self.music=nil;[self restoreAssistantMedia];[self audioRoute];[self log:@"Ilkutki завершился. Кнопка снова управляет голосом Butler."];}}
- (void)togglePocket {[self togglePocketAnnouncing:YES];}
- (void)togglePocketAnnouncing:(BOOL)announce {
    if(self.pocketMode){self.pocketMode=NO;self.sessionToken=nil;self.glassesVoiceEnabled=NO;[self cancelAgent];
        for(NSUInteger i=0;i<self.remoteCommands.count;i++){[self.remoteCommands[i] removeTarget:self.remoteTargets[i]];self.remoteCommands[i].enabled=NO;}
        self.remoteCommands=nil;self.remoteTargets=nil;MPNowPlayingInfoCenter.defaultCenter.nowPlayingInfo=nil;
        UIButtonConfiguration *style=self.pocketButton.configuration;style.title=@"Без экрана · включить тест";self.pocketButton.configuration=style;[self log:@"Тест без экрана выключен. Назначения очков можно вернуть во вкладке «Очки»."];return;
    }
    if(self.callActive || self.audioInterruptionActive){[self log:@"Дождитесь окончания звонка или другого аудио."];return;}
    if(!self.device.isConnectedAndReady){[self log:@"Сначала подключите очки."];return;}
    [self audioRoute];AVAudioSessionPortDescription *input=nil;for(AVAudioSessionPortDescription *p in AVAudioSession.sharedInstance.availableInputs)if([p.portType isEqual:AVAudioSessionPortBluetoothHFP] && ([p.portName.lowercaseString containsString:@"ai glasses"] || [p.portName.lowercaseString containsString:@"563b"]))input=p;
    if(!input || ![AVAudioSession.sharedInstance setPreferredInput:input error:nil]){[self log:@"Подключите звук AI Glasses в Settings → Bluetooth. Режим без экрана пока не включён."];return;}
    NSString *token=[self token];self.sessionToken=token;self.pocketMode=YES;self.glassesVoiceEnabled=YES;[self audioRoute];
    MPRemoteCommandCenter *center=MPRemoteCommandCenter.sharedCommandCenter;
    self.remoteCommands=@[center.togglePlayPauseCommand,center.playCommand,center.pauseCommand,center.nextTrackCommand,center.previousTrackCommand,center.stopCommand];self.remoteTargets=[NSMutableArray new];
    __weak typeof(self) weak=self;
    for(NSUInteger i=0;i<self.remoteCommands.count;i++){NSUInteger action=i;MPRemoteCommand *command=self.remoteCommands[i];command.enabled=YES;
        id handler=[command addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event){
            if(!weak.pocketMode || !weak.device.isConnectedAndReady || weak.callActive || weak.audioInterruptionActive)return MPRemoteCommandHandlerStatusCommandFailed;
            dispatch_async(dispatch_get_main_queue(),^{[weak log:[NSString stringWithFormat:@"Медиакоманда iOS: %lu.",(unsigned long)action]];
                if(weak.callActive || weak.audioInterruptionActive)return;
                if(weak.music){
                    if(action==0){if(weak.music.isPlaying)[weak.music pause];else [weak.music play];}
                    else if(action==1)[weak.music play];else if(action==2)[weak.music pause];else if(action==4){weak.music.currentTime=0;[weak.music play];}else if(action==5){[weak.music stop];weak.music=nil;[weak restoreAssistantMedia];}
                    MPNowPlayingInfoCenter.defaultCenter.playbackState=weak.music.isPlaying?MPNowPlayingPlaybackStatePlaying:MPNowPlayingPlaybackStatePaused;return;
                }
                if(action==2 && weak.startingVoice){[weak cancelAgent];return;}
                if(action<=2){if(action==0 || (action==1 && !weak.recorder) || (action==2 && weak.recorder))[weak voice];}
                else if(action==3){if(weak.lunch.active)[weak lunchPhoto];else [weak takePhoto];}
                else if(action==4){if(weak.lunch.active && !weak.recorder && !weak.startingVoice)[weak remoteBrief];else [weak repeatAnswer];}
                else [weak cancelAgent];
            });return MPRemoteCommandHandlerStatusSuccess;
        }];[self.remoteTargets addObject:handler];
    }
    [self restoreAssistantMedia];
    UIButtonConfiguration *style=self.pocketButton.configuration;style.title=@"Без экрана · выключить тест";self.pocketButton.configuration=style;
    [self log:@"Тест без экрана включён. Доставка кнопок и работа при блокировке ещё не проверены. Нет постоянной записи."];
    if(announce && ![NSProcessInfo.processInfo.arguments containsObject:@"--astor-music-probe"])[self speakAnswer:@"Режим без экрана включён. Двойное касание — вопрос, касание назад — повтор шага."];
}

- (void)stop {
    if([self.device conformsToProtocol:@protocol(AIBudsLiveStreamingAPI)])[(id<AIBudsLiveStreamingAPI>)self.device stopLiveStreaming];
}
@end

@interface AppDelegate : UIResponder <UIApplicationDelegate>
@property(nonatomic,strong) UIWindow *window;
@end
@implementation AppDelegate
- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)options {
    [AstorDockArchive shared];
    self.window=[[UIWindow alloc]initWithFrame:UIScreen.mainScreen.bounds];
    self.window.rootViewController=[ProbeController new]; [self.window makeKeyAndVisible]; return YES;
}
- (void)application:(UIApplication *)application handleEventsForBackgroundURLSession:(NSString *)identifier completionHandler:(void (^)(void))completion {
    if(![[AstorDockArchive shared] handleBackgroundSession:identifier completion:completion])completion();
}
@end
int main(int argc,char *argv[]) {@autoreleasepool{return UIApplicationMain(argc,argv,nil,NSStringFromClass(AppDelegate.class));}}
