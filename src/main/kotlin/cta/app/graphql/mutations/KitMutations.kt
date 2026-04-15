package cta.app.graphql.mutations

import cta.app.DeviceRequestRepository
import cta.app.DonorRepository
import cta.app.Kit
import cta.app.KitRepository
import cta.app.Note
import cta.app.QKit
import cta.app.services.FilterService
import cta.app.services.KitService
import cta.app.services.LocationService
import cta.app.services.MailService
import cta.toNullable
import jakarta.persistence.EntityNotFoundException
import jakarta.validation.Valid
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import java.time.Instant

@Controller
@Validated
@PreAuthorize("hasAnyAuthority('write:kits')")
@Transactional
class KitMutations(
    private val kits: KitRepository,
    private val donors: DonorRepository,
    private val deviceRequests: DeviceRequestRepository,
    private val locationService: LocationService,
    private val filterService: FilterService,
    private val mailService: MailService,
    private val kitService: KitService,
) {
    @MutationMapping
    fun createKit(
        @Argument @Valid data: CreateKitInput,
    ): Kit {
        val details = filterService.userDetails()
        val kit =
            kits.save(
                data.entity.apply {
                    if (location.isNotBlank()) {
                        coordinates = locationService.findCoordinates(location)
                    }

                    if (data.note != null) {
                        if (data.note.content !== "") {
                            val note = Note(content = data.note.content, kit = this, volunteer = details.email)
                            notes.add(note)
                        }
                    }

                    if (data.donorId != null) {
                        val user =
                            donors.findById(data.donorId).toNullable()
                                ?: throw EntityNotFoundException("Unable to locate a donor with id: ${data.donorId}")
                        data.entity.donor = user
                    }
                },
            )
        return kit
    }

    @MutationMapping
    fun quickCreateKit(
        @Argument @Valid data: QuickCreateKitInput,
    ): Kit {
        val kit =
            kits.save(
                data.entity.apply {
                    if (data.donorId != null) {
                        val user =
                            donors.findById(data.donorId).toNullable()
                                ?: throw EntityNotFoundException("Unable to locate a donor with id: ${data.donorId}")
                        data.entity.donor = user
                    }
                },
            )

        return kit
    }

    @MutationMapping
    fun updateKit(
        @Argument @Valid data: UpdateKitInput,
    ): Kit {
        val entity =
            kits.findOne(filterService.kitFilter().and(QKit.kit.id.eq(data.id))).toNullable()
                ?: throw EntityNotFoundException("Unable to locate a kit with id: ${data.id}")

        val previousStatus = entity.status
        return data.apply(entity).apply {
            // Update statusUpdatedAt if status has changed
            if (previousStatus != status) {
                statusUpdatedAt = Instant.now()
            }

            if (location.isNotBlank() && (coordinates == null || coordinates?.input != location)) {
                coordinates = locationService.findCoordinates(location)
            }

            if (data.donorId == null) {
                donor?.removeKit(this)
            } else if (data.donorId != donor?.id) {
                val user =
                    donors.findById(data.donorId).toNullable()
                        ?: throw EntityNotFoundException("Unable to locate a donor with id: ${data.donorId}")
                entity.donor = user
            }

            if (data.deviceRequestId == null) {
                deviceRequest?.removeKit(this)
            } else if (data.deviceRequestId != deviceRequest?.id) {
                val devRequest =
                    deviceRequests.findById(data.deviceRequestId).toNullable()
                        ?: throw EntityNotFoundException("Unable to locate a device request with id: ${data.deviceRequestId}")
                entity.deviceRequest = devRequest
            }

            if (data.note != null) {
                if (data.note.content !== "") {
                    val volunteer =
                        filterService.userDetails().name.ifBlank {
                            filterService.userDetails().email
                        }
                    val note = Note(content = data.note.content, kit = this, volunteer = volunteer)
                    notes.add(note)
                }
            }
        }
    }

    @MutationMapping
    fun autoCreateKit(
        @Argument @Valid data: AutoCreateKitInput,
    ): Kit {
        val entity = kits.findOne(filterService.kitFilter().and(QKit.kit.serialNo.eq(data.serialNo))).toNullable()

        /**
         * Create kit only if another Kit with the serial number does not exist. The philosophy is that as far as the
         * auto create script is concerned, the serial number is unique and if it is not, it is an edge case that falls
         * beyond the domain of it and requires manual intervention. We DO NOT want the script silently replacing Kit
         * details in case of a serialNo collision.
         */
        if (entity != null) {
            throw RuntimeException("Serial ${data.serialNo} exists with CTA ID# ${entity.id}")
        }

        return kits.save(
            data.entity.apply {
                if (data.donorId != null) {
                    val donor =
                        donors.findById(data.donorId).toNullable()
                            ?: throw EntityNotFoundException("Unable to locate a donor with id: ${data.donorId}")
                    data.entity.donor = donor
                }
            },
        )
    }

    @MutationMapping
    fun autoUpdateKit(
        @Argument @Valid data: AutoUpdateKitInput,
    ): Kit {
        val entity =
            kits.findOne(filterService.kitFilter().and(QKit.kit.id.eq(data.id))).toNullable()
                ?: throw RuntimeException("Unable to locate a kit with CTA id: ${data.id}")

        return data.apply(entity)
    }

    @MutationMapping
    fun updateKits(
        @Argument @Valid data: BulkKitUpdateInput,
    ): List<Kit> {
        val predicate =
            filterService
                .kitFilter()
                .and(QKit.kit.id.`in`(data.ids))
        val entities = kits.findAll(predicate)
        entities.forEach { data.apply(it) }
        return kits.saveAll(entities)
    }

    @PreAuthorize("hasAnyAuthority('delete:kits')")
    @MutationMapping
    fun deleteKit(
        @Argument id: Long,
    ): Boolean {
        val kit =
            kits.findOne(filterService.kitFilter().and(QKit.kit.id.eq(id))).toNullable()
                ?: throw EntityNotFoundException("Unable to locate a kit with id: $id")
        kits.delete(kit)
        return true
    }
}
