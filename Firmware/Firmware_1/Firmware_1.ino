#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>
#include <esp_sleep.h>

#define BUZZER_PIN 10
#define BUTTON_PIN GPIO_NUM_3
#define BATTERY_PIN 1 

#define SERVICE_UUID        "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define WRITE_CHAR_UUID     "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"

bool shouldBuzz = false;

// FIX 1: Use Arduino "String" instead of "std::string"
class MyCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) {
      String value = pCharacteristic->getValue(); // Corrected for version 3.3.7
      if (value.length() > 0 && value[0] == '1') {
        shouldBuzz = true; 
      }
    }
};

void setup() {
  Serial.begin(115200);
  pinMode(BUZZER_PIN, OUTPUT);
  pinMode(BUTTON_PIN, INPUT_PULLUP);

  esp_sleep_wakeup_cause_t wakeup_reason = esp_sleep_get_wakeup_cause();

  BLEDevice::init("ProxiNode");
  BLEServer *pServer = BLEDevice::createServer();
  BLEService *pService = pServer->createService(SERVICE_UUID);
  
  BLECharacteristic *pChar = pService->createCharacteristic(
                                         WRITE_CHAR_UUID,
                                         BLECharacteristic::PROPERTY_WRITE
                                       );
  pChar->setCallbacks(new MyCallbacks());
  pService->start();

  // FIX 2: Battery Advertisement for v3.3.7
  // We use BLEAdvertisementData because setManufacturerData belongs there
  int batteryLevel = map(analogRead(BATTERY_PIN), 0, 4095, 0, 100);
  
  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  
  BLEAdvertisementData pAdvertisementData;
  // Constructing manufacturer data: First 2 bytes are Company ID (0xFFFF for generic)
  String mData = "";
  mData += (char)0xFF; mData += (char)0xFF; 
  mData += (char)batteryLevel; 
  
  pAdvertisementData.setManufacturerData(mData.c_str()); 
  pAdvertising->setAdvertisementData(pAdvertisementData);
  
  pAdvertising->start();
  delay(200); 

  if (shouldBuzz) {
      tone(BUZZER_PIN, 2700, 1000); 
      delay(1100);
  }

  esp_deep_sleep_enable_gpio_wakeup(1 << BUTTON_PIN, ESP_GPIO_WAKEUP_GPIO_LOW);
  esp_sleep_enable_timer_wakeup(2 * 1000000); 
  esp_deep_sleep_start();
}

void loop() {}