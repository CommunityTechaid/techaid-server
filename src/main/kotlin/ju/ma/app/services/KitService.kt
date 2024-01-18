package ju.ma.app.services

import ju.ma.app.KitType
import org.springframework.stereotype.Service

@Service
class KitService {

    //This lookup is from the official documentation PDF of System Management BIOS (SMBIOS)
    //refer https://superuser.com/questions/877677/programatically-determine-if-an-script-is-being-executed-on-laptop-or-desktop
    fun lookupDeviceType(deviceTypeId: Int): KitType {

        return when (deviceTypeId) {
            3, 4, 6, 7, 12, 24 -> KitType.DESKTOP
            8, 9, 10, 14, 31 -> KitType.LAPTOP
            30 -> KitType.TABLET
            13 -> KitType.ALLINONE
            else -> {
                KitType.OTHER
            }
        };
    }
}