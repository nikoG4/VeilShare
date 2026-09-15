from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    s = p.read_text()
    assert s.count(old) == 1, (path, s.count(old), old[:100])
    p.write_text(s.replace(old, new, 1))

# Runtime: do not translate coroutine cancellation into verification failure.
path = 'shared/app/src/commonMain/kotlin/dev/veilshare/app/DefaultSharingRuntime.kt'
replace_once(path,
'''        } catch (_: Throwable) {
            stateMutex.withLock { pendingVerification = null }
            SharingPeerLookupResult.Failed("No se pudo comprobar la identidad del contacto.")
        }
    }

    override suspend fun send(''',
'''        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            stateMutex.withLock { pendingVerification = null }
            SharingPeerLookupResult.Failed("No se pudo comprobar la identidad del contacto.")
        }
    }

    override suspend fun send(''')

# Controller: verification jobs must preserve explicit cancellation/terminal UI state.
path = 'shared/ui-features/src/commonMain/kotlin/dev/veilshare/ui/features/LocalAppController.kt'
replace_once(path,
'''            } catch (_: Exception) {
                mutableState.value = RootState.SharingSender(
                    verification.copy(busy = false, error = "No se pudo guardar la verificación."),
                )
            } finally {
                sharingJob = null
            }
        }
    }

    fun dismissSharingVerification()''',
'''            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = RootState.SharingSender(
                    verification.copy(busy = false, error = "No se pudo guardar la verificación."),
                )
            } finally {
                sharingJob = null
            }
        }
    }

    fun dismissSharingVerification()''')
replace_once(path,
'''            } catch (_: Exception) {
                mutableState.value = RootState.SharingContactVerification(
                    SharingContactVerificationState.Entering(referenceCode.value, error = "No se pudo comprobar el contacto."),
                )
            } finally {
                sharingJob = null
            }
        }
    }

    fun confirmContactVerification(alias: String)''',
'''            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = RootState.SharingContactVerification(
                    SharingContactVerificationState.Entering(referenceCode.value, error = "No se pudo comprobar el contacto."),
                )
            } finally {
                sharingJob = null
            }
        }
    }

    fun confirmContactVerification(alias: String)''')
replace_once(path,
'''            } catch (_: Exception) {
                mutableState.value = RootState.SharingContactVerification(
                    verification.copy(busy = false, error = "No se pudo guardar la verificación."),
                )
            } finally {
                sharingJob = null
            }
        }
    }

    fun dismissContactVerification()''',
'''            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = RootState.SharingContactVerification(
                    verification.copy(busy = false, error = "No se pudo guardar la verificación."),
                )
            } finally {
                sharingJob = null
            }
        }
    }

    fun dismissContactVerification()''')
